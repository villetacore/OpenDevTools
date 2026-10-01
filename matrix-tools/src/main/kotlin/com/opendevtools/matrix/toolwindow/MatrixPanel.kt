package com.opendevtools.matrix.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.matrix.model.MatrixMessage
import com.opendevtools.matrix.model.MatrixRoom
import com.opendevtools.matrix.model.MatrixSnapshot
import com.opendevtools.matrix.model.RoomMembership
import com.opendevtools.matrix.service.MatrixService
import com.opendevtools.matrix.service.SyncListener
import com.opendevtools.matrix.settings.MatrixConfigurable
import com.opendevtools.matrix.settings.MatrixSettings
import com.opendevtools.matrix.settings.MatrixStateListener
import com.opendevtools.matrix.util.MatrixNotifier
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * The Matrix tool window: room list on one side, timeline and composer on the other.
 *
 * The panel is a listener on the sync loop rather than a poller - every update arrives because
 * the homeserver had something to say.
 */
class MatrixPanel(private val project: Project, parentDisposable: Disposable) :
    SimpleToolWindowPanel(true, true), UiDataProvider, Disposable {

    private val listModel = DefaultListModel<RoomListEntry>()

    private val roomList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RoomListCellRenderer()
    }

    private val searchField = SearchTextField().apply {
        textEditor.emptyText.text = "Filter rooms"
        border = JBUI.Borders.empty(2)
    }

    private val timeline = TimelinePanel(onLoadEarlier = { loadEarlier() })

    private val composer = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4)
        emptyText.text = "Write a message, Enter to send, Shift+Enter for a new line"
    }

    private val sendButton = JButton("Send").apply { addActionListener { send() } }

    private var snapshot: MatrixSnapshot = MatrixSnapshot.EMPTY
    private var selectedRoomId: String? = null

    @Volatile
    private var disposed = false

    private val syncListener = SyncListener { updated, arrivals ->
        Background.onEdt { onSnapshot(updated, arrivals) }
    }

    init {
        Disposer.register(parentDisposable, this)

        val group = ActionManager.getInstance().getAction("MatrixTools.Toolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("MatrixToolWindow", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val rooms = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(roomList, true), BorderLayout.CENTER)
        }

        val composerRow = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(2)
            add(ScrollPaneFactory.createScrollPane(composer, true), BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }
        val conversation = JPanel(BorderLayout()).apply {
            add(timeline, BorderLayout.CENTER)
            add(composerRow, BorderLayout.SOUTH)
        }

        setContent(
            OnePixelSplitter(false, 0.32f).apply {
                firstComponent = rooms
                secondComponent = conversation
            },
        )

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuildRoomList()
        })
        roomList.addListSelectionListener {
            if (!it.valueIsAdjusting) onRoomSelected()
        }
        PopupHandler.installPopupMenu(roomList, "MatrixTools.RoomMenu", "MatrixRoomPopup")

        // Enter sends, Shift+Enter inserts a newline - the convention every chat client uses.
        composer.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    send()
                }
            }
        })

        MatrixService.getInstance().addListener(syncListener)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(MatrixSettings.TOPIC, MatrixStateListener { connect() })

        connect()
    }

    override fun dispose() {
        disposed = true
        MatrixService.getInstance().removeListener(syncListener)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink.set(MatrixDataKeys.PANEL, this)
        selectedRoom()?.let { sink.set(MatrixDataKeys.ROOM, it) }
    }

    // --- State -----------------------------------------------------------------------------------

    fun selectedRoom(): MatrixRoom? = selectedRoomId?.let { snapshot.room(it) }

    fun currentSnapshot(): MatrixSnapshot = snapshot

    /** Starts or restarts the sync loop after a settings change. */
    fun connect() {
        if (disposed) return
        val service = MatrixService.getInstance()
        if (!MatrixSettings.getInstance().isComplete) {
            snapshot = MatrixSnapshot.EMPTY
            listModel.clear()
            timeline.showNothing()
            roomList.emptyText
                .setText("Matrix account is not configured")
                .appendLine("Sign in", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { openSettings() }
            return
        }
        roomList.emptyText.text = "Connecting..."
        service.startSync()
        onSnapshot(service.currentSnapshot(), emptyList())
    }

    private fun onSnapshot(updated: MatrixSnapshot, arrivals: List<MatrixMessage>) {
        if (disposed) return
        snapshot = updated
        rebuildRoomList()
        selectedRoom()?.let { timeline.show(it, snapshot.userId) }
        announce(arrivals)
    }

    /** Balloons for new traffic, filtered by what the user asked to hear about. */
    private fun announce(arrivals: List<MatrixMessage>) {
        if (arrivals.isEmpty()) return
        val settings = MatrixSettings.getInstance()
        val interesting = arrivals.filter { message ->
            when {
                settings.notifyOnAnyMessage -> true
                settings.notifyOnMention -> message.mentions(snapshot.userId)
                else -> false
            }
        }
        // A room the user is looking at right now does not need a balloon.
        interesting.filterNot { it.roomId == selectedRoomId }
            .groupBy { it.roomId }
            .forEach { (roomId, messages) ->
                val room = snapshot.room(roomId)?.displayName ?: roomId
                val first = messages.first()
                val suffix = if (messages.size > 1) " (+${messages.size - 1} more)" else ""
                MatrixNotifier.info(
                    project,
                    "$room - ${first.senderDisplayName}: ${first.body.take(NOTIFICATION_LENGTH)}$suffix",
                )
            }
    }

    private fun rebuildRoomList() {
        val settings = MatrixSettings.getInstance()
        val entries = RoomListBuilder.build(
            snapshot.rooms,
            settings.grouping,
            searchField.text,
            settings.showEncryptedRooms,
        )
        val previous = selectedRoomId

        listModel.clear()
        entries.forEach { listModel.addElement(it) }

        roomList.emptyText.text = when {
            snapshot.rooms.isEmpty() -> "No rooms yet"
            entries.isEmpty() -> "Nothing matches the filter"
            else -> ""
        }

        // Keep the selection on the same room across a rebuild.
        val index = entries.indexOfFirst { it is RoomListEntry.Room && it.room.id == previous }
        if (index >= 0) roomList.selectedIndex = index
    }

    private fun onRoomSelected() {
        val entry = roomList.selectedValue
        if (entry !is RoomListEntry.Room) {
            // A header row is not selectable in spirit; keep the previous conversation on screen.
            return
        }
        selectedRoomId = entry.room.id
        timeline.show(entry.room, snapshot.userId)
        composer.isEnabled = entry.room.membership == RoomMembership.JOINED && !entry.room.encrypted
        sendButton.isEnabled = composer.isEnabled

        if (MatrixSettings.getInstance().markReadOnOpen && entry.room.hasUnread) {
            val roomId = entry.room.id
            Background.run({ MatrixService.getInstance().markRead(roomId) }) { }
        }
    }

    // --- Commands ----------------------------------------------------------------------------------

    private fun send() {
        val roomId = selectedRoomId ?: return
        val text = composer.text.trim()
        if (text.isEmpty()) return
        composer.text = ""

        Background.run({ MatrixService.getInstance().sendMessage(roomId, text) }) { outcome ->
            if (outcome is Outcome.Failure) {
                MatrixNotifier.failure(project, "Could not send the message", outcome.error)
                // Give the text back rather than losing what the user typed.
                if (composer.text.isBlank()) composer.text = text
            }
        }
    }

    private fun loadEarlier() {
        val roomId = selectedRoomId ?: return
        Background.run({ MatrixService.getInstance().loadEarlier(roomId) }) { outcome ->
            when (outcome) {
                is Outcome.Failure ->
                    MatrixNotifier.failure(project, "Could not load earlier messages", outcome.error)

                is Outcome.Success -> outcome.value?.let { timeline.show(it, snapshot.userId) }
            }
        }
    }

    fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, MatrixConfigurable::class.java)
    }

    /** Selects a room by id, used after joining one. */
    fun selectRoom(roomId: String) {
        val index = (0 until listModel.size()).firstOrNull {
            (listModel.getElementAt(it) as? RoomListEntry.Room)?.room?.id == roomId
        } ?: return
        roomList.selectedIndex = index
        roomList.ensureIndexIsVisible(index)
    }

    fun selectedTimelineText(): String = timeline.selectedText()

    private companion object {
        const val NOTIFICATION_LENGTH = 140
    }
}
