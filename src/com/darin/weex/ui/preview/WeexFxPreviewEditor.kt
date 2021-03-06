package com.darin.weex.ui.preview

import com.darin.weex.WeexAppConfig
import com.darin.weex.utils.TransformTasks
import com.darin.weex.utils.WeexConstants
import com.darin.weex.utils.WeexUtils
import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.ui.JBSplitter
import com.intellij.util.Alarm
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.paint.Color
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Created by darin on 5/18/16.
 */
class WeexFxPreviewEditor(project: Project, file: VirtualFile) : UserDataHolderBase(), TextEditor, FileEditor, Disposable {

    private val mMainEditorProvider = PsiAwareTextEditorProvider()

    private val mUpdatePreviewWidthAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    lateinit private var mCurrentModifyFile: VirtualFile
    private var mPreviewPanel: JPanel? = null
    lateinit private var mMainEditor: FileEditor
    private var mJavaFxView: JFXPanel? = null
    private var splitter: JBSplitter? = null
    lateinit private var mFinalView: JPanel
    /**
     * @return get current split layout
     */
    var currentEditorLayout = SplitEditorLayout.SPLIT
        private set
    lateinit var myToolbarWrapper: WeexSplitEditorToolbar
        private set
    lateinit private var weexBrowser: WeexBrowser
    lateinit private var listerner: WeexUtils.onLocalServerStatusChangeListener
    private var mUpdatePreviewWidthRunnable: Runnable? = null

    init {
        initData(project, file)
        initUi()
    }

    private fun initData(p: Project, file: VirtualFile) {
        this.mCurrentModifyFile = file
        mMainEditor = mMainEditorProvider.createEditor(p, file)
        mMainEditor.putUserData(PARENT_SPLIT_KEY, this)
    }

    private fun initPreviewUi() {
        mPreviewPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        mJavaFxView = JFXPanelWrapper() // initializing javafx
        mPreviewPanel!!.add(mJavaFxView)
        rePaintView(currentEditorLayout)

        splitter = JBSplitter(false, WeexAppConfig.splitProportion, 0.15f, 0.85f)

        initWebview(webviewWidth, webviewHeight)
        splitter!!.splitterProportionKey = ""

        splitter!!.firstComponent = mMainEditor.component
        splitter!!.secondComponent = mPreviewPanel
        splitter!!.addPropertyChangeListener(PropertyChangeListener {
            if (mUpdatePreviewWidthRunnable != null)
                mUpdatePreviewWidthAlarm.cancelRequest(mUpdatePreviewWidthRunnable!!)

            val second = splitter!!.secondComponent
            val width = second.width
            val height = second.height

            if (webviewWidth == width)
                return@PropertyChangeListener

            WeexUtils.println("Width = $width, height = $height")

            mUpdatePreviewWidthRunnable = Runnable {
                if (width != 0 && height != 0) {
                    webviewHeight = height
                    webviewWidth = width

                    initWebview(width, height)

                    WeexAppConfig.splitProportion = (splitter!!.proportion)
                    WeexAppConfig.webviewHeight = (height)
                    WeexAppConfig.webviewWidth = (width)

                }
            }

            if (!mUpdatePreviewWidthAlarm.isDisposed)
                mUpdatePreviewWidthAlarm.addRequest(mUpdatePreviewWidthRunnable!!, 20L)
        })
        myToolbarWrapper = WeexSplitEditorToolbar(splitter!!)
        mFinalView.remove(myToolbarWrapper)
        mFinalView.remove(mMainEditor.component)
        mFinalView.add(myToolbarWrapper, BorderLayout.NORTH)
        mFinalView.add(splitter, BorderLayout.CENTER)
        mFinalView.updateUI()
        TransformTasks.instance.addTransformTask(mCurrentModifyFile)
    }


    private fun initUi() {
        myToolbarWrapper = WeexSplitEditorToolbar(mMainEditor.component)
        mFinalView = JPanel(BorderLayout())
        mFinalView.add(myToolbarWrapper, BorderLayout.NORTH)
        mFinalView.add(mMainEditor.component, BorderLayout.CENTER)


        listerner = object : WeexUtils.onLocalServerStatusChangeListener {
            override fun onLocalServerStatusChange(isOn: Boolean) {
                ApplicationManager.getApplication().invokeLater {
                    /**
                     * update the server button
                     */
                    mFinalView.remove(myToolbarWrapper)
                    myToolbarWrapper = WeexSplitEditorToolbar(if (WeexConstants.hasJavaFx() || splitter == null) mMainEditor.component else splitter!!)
                    mFinalView.add(myToolbarWrapper, BorderLayout.NORTH)
                    mFinalView.updateUI()
                    rePaintView(currentEditorLayout)
                }
                if (isOn) {
                    TransformTasks.instance.addTransformTask(mCurrentModifyFile)
                    reLoad()
                }

            }

        }

        WeexUtils.addServerChangeListener(listerner)

        /**
         * new JFXPanel() may block the ui thread, so we invoke it later
         */
        if (WeexConstants.hasJavaFx())
            WeexConstants.invokeLater(object : Runnable {
                override fun run() {
                    initPreviewUi()
                }

            })

    }

    private fun reLoad() {
        if (WeexConstants.hasJavaFx())
            TransformTasks.instance.addLoadUrlTask(mCurrentModifyFile.path)
    }

    private fun initWebviewWithComponent() {
        webviewWidth = (mFinalView.width * (1 - splitter!!.proportion)).toInt()
        initWebview(webviewWidth, mFinalView.height)
    }


    private fun initWebview(width: Int, height: Int) {
        runLater(Runnable {
            weexBrowser = WeexBrowser(width, height, mCurrentModifyFile.path)

            val scene = Scene(weexBrowser, width.toDouble(), height.toDouble(), Color.web("#666970"))
            mJavaFxView!!.scene = scene
            reLoad()
        })
    }


    override fun getComponent(): JComponent {
        return mFinalView
    }

    override fun getPreferredFocusedComponent(): JComponent? {
        return mFinalView
    }

    override fun getName(): String {
        return PREVIEW_EDITOR_NAME
    }

    override fun getState(fileEditorStateLevel: FileEditorStateLevel): FileEditorState {
        return FileEditorState.INSTANCE
    }

    override fun setState(fileEditorState: FileEditorState) {

    }

    override fun isModified(): Boolean {
        return false
    }

    override fun isValid(): Boolean {
        return true
    }

    override fun selectNotify() {
    }

    override fun deselectNotify() {

    }

    override fun addPropertyChangeListener(propertyChangeListener: PropertyChangeListener) {

    }

    override fun removePropertyChangeListener(propertyChangeListener: PropertyChangeListener) {

    }

    override fun getBackgroundHighlighter(): BackgroundEditorHighlighter? {
        return null
    }

    override fun getCurrentLocation(): FileEditorLocation? {
        return null
    }

    override fun getStructureViewBuilder(): StructureViewBuilder? {
        return null
    }

    override fun dispose() {
        mMainEditor.dispose()
        WeexUtils.removeServerChangeListener(listerner)
    }

    /**
     * @return the TextEditor that we can edit code
     */
    override fun getEditor(): Editor {
        return (mMainEditor as TextEditor).editor
    }

    override fun canNavigateTo(navigatable: Navigatable): Boolean {
        return (mMainEditor as TextEditor).canNavigateTo(navigatable)
    }

    override fun navigateTo(navigatable: Navigatable) {
        (mMainEditor as TextEditor).navigateTo(navigatable)
    }

    /**
     * @return Text editor component
     */
    private val editComponent: JComponent
        get() = mMainEditor.component

    /**
     * @return Preview edtior component
     */
    private val previewComponent: JComponent
        get() = mPreviewPanel!!

    /**
     * reset the layout with the spliteditorlayout

     * @param layout [SplitEditorLayout] the editor layout
     */
    fun rePaintView(layout: SplitEditorLayout) {
        if (!WeexConstants.hasJavaFx())
            return
        editComponent.isVisible = layout.showEditor
        previewComponent.isVisible = layout.showPreview
        myToolbarWrapper.refresh()
        currentEditorLayout = layout
    }

    private fun runLater(runnable: Runnable) {
        Platform.runLater(runnable)
    }

    /**
     * three layouts of split preview
     * 1. text area only
     * 2. preview only
     * 3. both text an preview
     */
    enum class SplitEditorLayout constructor(val showEditor: Boolean, val showPreview: Boolean, val presentationName: String) {
        EDITOR_ONLY(true, false, "Text"),
        PREVIEW_ONLY(false, true, "Preview"),
        SPLIT(true, true, "Split");

        override fun toString(): String {
            return presentationName
        }
    }

    companion object {
        val PARENT_SPLIT_KEY = Key.create<WeexFxPreviewEditor>("parentSplit")

        private val PREVIEW_EDITOR_NAME = "HTML5"

        private var webviewHeight = WeexAppConfig.webviewHeight
        private var webviewWidth = WeexAppConfig.webviewWidth
    }
}
