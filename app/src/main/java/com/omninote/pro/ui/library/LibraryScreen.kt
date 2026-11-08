package com.omninote.pro.ui.library

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MenuOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omninote.pro.data.FolderEntity
import com.omninote.pro.ui.workspace.ChipButton
import com.omninote.pro.ui.workspace.SectionLabel
import com.omninote.pro.ui.workspace.ToolButton
import com.omninote.pro.ui.workspace.Warm
import com.omninote.pro.ui.workspace.WorkspaceTheme
import com.omninote.pro.ui.workspace.glass

private val LibraryBackground = Color(0xFF11192B)

private sealed interface Dlg {
    data object NewNotebook : Dlg
    data class NewFolder(val parentId: String?) : Dlg
    data class RenameFolder(val folder: FolderEntity) : Dlg
    data class DeleteFolder(val folder: FolderEntity) : Dlg
    data class MoveFolder(val folder: FolderEntity) : Dlg
    data class RenameNotebook(val card: NotebookCard) : Dlg
    data class DeleteNotebook(val card: NotebookCard) : Dlg
    data class MoveNotebook(val card: NotebookCard) : Dlg
    data class Tags(val card: NotebookCard) : Dlg
}

/**
 * Master-detail library: a collapsible sidebar (all, starred, nested folders, tag filters) beside the notebook grid,
 * with an instant search box that matches titles, tags and recognised handwriting.
 */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpenNotebook: (notebookId: String, pageIndex: Int) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    WorkspaceTheme {
        val section by vm.section.collectAsState()
        val cards by vm.cards.collectAsState()
        val folders by vm.folders.collectAsState()
        val tags by vm.allTags.collectAsState()
        val activeTags by vm.activeTags.collectAsState()
        val query by vm.query.collectAsState()
        val search by vm.search.collectAsState()
        val importState by vm.importState.collectAsState()
        val total by vm.totalNotebookCount.collectAsState()

        var sidebarOpen by rememberSaveable { mutableStateOf(true) }
        var kind by rememberSaveable { mutableStateOf(LibraryKind.ALL) }
        var dialog by remember { mutableStateOf<Dlg?>(null) }
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(vm) {
            vm.events.collect { ev ->
                when (ev) {
                    is LibraryEvent.Open -> onOpenNotebook(ev.notebookId, ev.pageIndex)
                    is LibraryEvent.Message -> {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(ev.text)
                    }
                }
            }
        }

        val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importPdf(uri) }
        val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importArchive(uri) }

        val folderNames = remember(folders) { folders.associate { it.folderId to it.name } }

        BoxWithConstraints(modifier.fillMaxSize().background(LibraryBackground).windowInsetsPadding(WindowInsets.systemBars)) {
            // Below 720dp (split-screen halves, phones) the sidebar becomes a drawer behind a hamburger.
            val compact = maxWidth < 720.dp
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            BackHandler(enabled = compact && drawerState.currentValue == DrawerValue.Open) {
                scope.launch { drawerState.close() }
            }

            val sidebar: @Composable (Boolean) -> Unit = { inDrawer ->
              Box(Modifier.fillMaxHeight().background(Color(0xFF11192B))) {
                NightMountains(Modifier.matchParentSize())
                Sidebar(
                    open = inDrawer || sidebarOpen,
                    section = section,
                    folders = folders,
                    tags = tags.map { it.name },
                    activeTags = activeTags,
                    total = total,
                    onToggle = { if (inDrawer) scope.launch { drawerState.close() } else sidebarOpen = !sidebarOpen },
                    onSection = { vm.showSection(it); if (inDrawer) scope.launch { drawerState.close() } },
                    onToggleTag = vm::toggleTag,
                    onClearTags = vm::clearTags,
                    onNewFolder = { dialog = Dlg.NewFolder(it) },
                    onRenameFolder = { dialog = Dlg.RenameFolder(it) },
                    onMoveFolder = { dialog = Dlg.MoveFolder(it) },
                    onDeleteFolder = { dialog = Dlg.DeleteFolder(it) }
                )
              }
            }

            val mainColumn: @Composable (Modifier) -> Unit = { m ->
                Column(m.fillMaxHeight().padding(horizontal = if (compact) 12.dp else 18.dp, vertical = 14.dp)) {
                    TopRow(
                        query = query,
                        onQuery = vm::setQuery,
                        onNew = { dialog = Dlg.NewNotebook },
                        onImportPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                        onImportArchive = { archivePicker.launch(arrayOf("*/*")) },
                        onSettings = onOpenSettings,
                        onMenu = if (compact) ({ scope.launch { drawerState.open() } }) else null
                    )
                    Box(Modifier.height(12.dp))
                    val results = search
                    if (results != null) {
                        SearchResults(
                            state = results,
                            folderNames = folderNames,
                            onOpen = onOpenNotebook,
                            onAction = { dialog = it },
                            onStar = vm::toggleStar
                        )
                    } else {
                        SectionHeader(section, folders, activeTags, vm::clearTags)
                        Box(Modifier.height(8.dp))
                        KindTabs(kind) { kind = it }
                        Box(Modifier.height(10.dp))
                        val shownCards = when (kind) {
                            LibraryKind.ALL -> cards
                            LibraryKind.NOTE -> cards.filter { !it.isPdf }
                            LibraryKind.PDF -> cards.filter { it.isPdf }
                        }
                        if (cards.isEmpty()) {
                            EmptyState(section, activeTags.isNotEmpty(), total == 0) { dialog = Dlg.NewNotebook }
                        } else {
                            NotebookGrid(shownCards, folderNames, onOpenNotebook, { dialog = it }, vm::toggleStar, onCreate = { dialog = Dlg.NewNotebook })
                        }
                    }
                }
            }

            if (compact) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(drawerContainerColor = Color(0xFF11192B), modifier = Modifier.width(300.dp)) { sidebar(true) }
                    }
                ) {
                    mainColumn(Modifier.fillMaxSize())
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    sidebar(false)
                    mainColumn(Modifier.weight(1f))
                }
            }

            // Always reachable, whatever the width: the new-notebook button.
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .glass(RoundedCornerShape(50), raised = true, elevation = 14.dp)
                    .background(Warm.Accent, RoundedCornerShape(50))
                    .clickable { dialog = Dlg.NewNotebook }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Add, "New notebook", tint = Color(0xFFFFFFFF))
                Text("New Notebook", color = Color(0xFFFFFFFF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { data ->
                Snackbar(data, containerColor = Warm.GlassRaised, contentColor = Warm.Text, actionColor = Warm.Accent)
            }
        }

        // ---------------------------------------------------------------- dialogs
        when (val d = dialog) {
            null -> Unit
            Dlg.NewNotebook -> NewNotebookDialog(vm.currentFolderId(), vm::createNotebook) { dialog = null }
            is Dlg.NewFolder -> PromptDialog("New folder", "", "Folder name", "Create", { vm.createFolder(it, d.parentId) }) { dialog = null }
            is Dlg.RenameFolder -> PromptDialog("Rename folder", d.folder.name, "Folder name", "Rename", { vm.renameFolder(d.folder.folderId, it) }) { dialog = null }
            is Dlg.DeleteFolder -> ConfirmDialog(
                "Delete “${d.folder.name}”?",
                "Its sub-folders are deleted too. Notebooks inside are kept and move up one level.",
                "Delete", { vm.deleteFolder(d.folder.folderId) }
            ) { dialog = null }
            is Dlg.MoveFolder -> FolderPickerDialog(
                "Move “${d.folder.name}” to", folders, excluded = subtreeOf(folders, d.folder.folderId), allowTopLevel = true,
                onPick = { target -> vm.moveFolderTo(d.folder.folderId, target) }
            ) { dialog = null }
            is Dlg.RenameNotebook -> PromptDialog("Rename notebook", d.card.entity.title, "Title", "Rename", { vm.renameNotebook(d.card.entity.notebookId, it) }) { dialog = null }
            is Dlg.DeleteNotebook -> ConfirmDialog(
                "Delete “${d.card.entity.title}”?",
                "All its pages, images and audio are removed from this device. Export a .omninote archive first if you may want it back.",
                "Delete", { vm.deleteNotebook(d.card.entity.notebookId) }
            ) { dialog = null }
            is Dlg.MoveNotebook -> FolderPickerDialog(
                "Move to folder", folders, excluded = emptySet(), allowTopLevel = true,
                onPick = { target -> vm.moveNotebook(d.card.entity.notebookId, target) }
            ) { dialog = null }
            is Dlg.Tags -> TagEditDialog(d.card.tags, tags.map { it.name }, { vm.setTags(d.card.entity.notebookId, it) }) { dialog = null }
        }

        importState?.let { ImportProgressDialog(it, onCancel = vm::cancelImport, onDismissError = vm::dismissImportError) }
    }
}

private fun subtreeOf(folders: List<FolderEntity>, rootId: String): Set<String> {
    val out = HashSet<String>()
    fun walk(id: String) {
        if (!out.add(id)) return
        for (f in folders) if (f.parentFolderId == id) walk(f.folderId)
    }
    walk(rootId)
    return out
}

// =============================================================================================
// Sidebar
// =============================================================================================

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun Sidebar(
    open: Boolean,
    section: LibrarySection,
    folders: List<FolderEntity>,
    tags: List<String>,
    activeTags: Set<String>,
    total: Int,
    onToggle: () -> Unit,
    onSection: (LibrarySection) -> Unit,
    onToggleTag: (String) -> Unit,
    onClearTags: () -> Unit,
    onNewFolder: (parentId: String?) -> Unit,
    onRenameFolder: (FolderEntity) -> Unit,
    onMoveFolder: (FolderEntity) -> Unit,
    onDeleteFolder: (FolderEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val rows = remember(folders, expanded) {
        val hasParentIn = folders.groupBy { it.parentFolderId }
        flattenFolders(folders).filter { (f, _) ->
            // Visible when every ancestor is expanded.
            var p = f.parentFolderId
            var visible = true
            while (p != null) {
                if (p !in expanded) { visible = false; break }
                p = folders.firstOrNull { it.folderId == p }?.parentFolderId
            }
            visible && (hasParentIn.isNotEmpty())
        }
    }
    val childCount = remember(folders) { folders.groupingBy { it.parentFolderId }.eachCount() }

    Column(
        Modifier
            .fillMaxHeight()
            .width(if (open) 280.dp else 76.dp)
            .animateContentSize()
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (open) Text("OmniNote Pro", color = Warm.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 6.dp))
            ToolButton(selected = false, onClick = onToggle, size = 44.dp) {
                Icon(if (open) Icons.Rounded.MenuOpen else Icons.Rounded.Menu, "Toggle sidebar", tint = Warm.Text)
            }
        }
        NavItem(Icons.Rounded.LibraryBooks, "All notebooks", open, section == LibrarySection.All, if (open) total.toString() else null) { onSection(LibrarySection.All) }
        NavItem(Icons.Rounded.Star, "Starred", open, section == LibrarySection.Starred, null) { onSection(LibrarySection.Starred) }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (open) SectionLabel("Folders", Modifier.weight(1f))
            ToolButton(selected = false, onClick = { onNewFolder(null) }, size = 40.dp) {
                Icon(Icons.Rounded.CreateNewFolder, "New folder", tint = Warm.Text)
            }
        }
        if (open) {
            for ((f, depth) in rows) {
                val isSelected = section is LibrarySection.Folder && section.folderId == f.folderId
                val hasKids = (childCount[f.folderId] ?: 0) > 0
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) Warm.AccentSoft else Color.Transparent)
                        .combinedClickable(onClick = { onSection(LibrarySection.Folder(f.folderId)) }, onLongClick = { menu = true })
                        .padding(start = (4 + depth * 14).dp, end = 2.dp)
                        .height(44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(28.dp).clickable(enabled = hasKids) {
                            expanded = if (f.folderId in expanded) expanded - f.folderId else expanded + f.folderId
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        if (hasKids) Icon(
                            if (f.folderId in expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                            "Expand", tint = Warm.TextMuted, modifier = Modifier.size(20.dp)
                        )
                    }
                    Icon(Icons.Rounded.Folder, null, tint = if (isSelected) Warm.Accent else Warm.TextMuted, modifier = Modifier.size(20.dp))
                    Text(f.name, color = Warm.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    Box {
                        Box(Modifier.size(32.dp).clickable { menu = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.MoreVert, "Folder options", tint = Warm.TextMuted, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("New sub-folder") }, { menu = false; expanded = expanded + f.folderId; onNewFolder(f.folderId) })
                            DropdownMenuItem({ Text("Rename") }, { menu = false; onRenameFolder(f) })
                            DropdownMenuItem({ Text("Move…") }, { menu = false; onMoveFolder(f) })
                            DropdownMenuItem({ Text("Delete", color = Warm.Danger) }, { menu = false; onDeleteFolder(f) })
                        }
                    }
                }
            }
            if (folders.isEmpty()) Text("No folders yet.", color = Warm.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))

            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Tags", Modifier.weight(1f))
                if (activeTags.isNotEmpty()) ChipButton("Clear", false, onClearTags)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val shown = (tags + com.omninote.pro.core.model.NotebookTag.DEFAULTS.map { it.name }).distinct()
                for (t in shown) ChipButton(t, t in activeTags, { onToggleTag(t) })
            }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, open: Boolean, selected: Boolean, badge: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Warm.AccentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = if (open) 12.dp else 0.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (open) Arrangement.Start else Arrangement.Center
    ) {
        Icon(icon, label, tint = if (selected) Warm.Accent else Warm.TextMuted, modifier = Modifier.size(24.dp))
        if (open) {
            Text(label, color = Warm.Text, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
            if (badge != null) Text(badge, color = Warm.TextMuted, fontSize = 12.sp)
        }
    }
}

// =============================================================================================
// Main column
// =============================================================================================

@Composable
private fun TopRow(
    query: String,
    onQuery: (String) -> Unit,
    onNew: () -> Unit,
    onImportPdf: () -> Unit,
    onImportArchive: () -> Unit,
    onSettings: () -> Unit,
    onMenu: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onMenu != null) {
            ToolButton(selected = false, onClick = onMenu, size = 44.dp) { Icon(Icons.Rounded.Menu, "Open menu", tint = Warm.Text) }
        }
        Row(
            Modifier
                .weight(1f)
                .glass(RoundedCornerShape(50), elevation = 0.dp)
                .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, "Search", tint = Warm.TextMuted)
            Box(Modifier.weight(1f).padding(start = 8.dp)) {
                com.omninote.pro.ui.workspace.PanelTextField(query, "Search titles, tags and handwriting", Modifier, onQuery)
            }
        }
        if (onMenu == null) ChipButton("New notebook", true, onNew)
        ToolButton(selected = false, onClick = onImportPdf, size = 44.dp) { Icon(Icons.Rounded.PictureAsPdf, "Import PDF textbook", tint = Warm.Text) }
        ToolButton(selected = false, onClick = onImportArchive, size = 44.dp) { Icon(Icons.Rounded.Unarchive, "Import .omninote archive", tint = Warm.Text) }
        ToolButton(selected = false, onClick = onSettings, size = 44.dp) { Icon(Icons.Rounded.Settings, "Settings", tint = Warm.Text) }
    }
}

@Composable
private fun SectionHeader(section: LibrarySection, folders: List<FolderEntity>, activeTags: Set<String>, onClearTags: () -> Unit) {
    val title = when (section) {
        LibrarySection.All -> "All notebooks"
        LibrarySection.Starred -> "Starred"
        is LibrarySection.Folder -> folders.firstOrNull { it.folderId == section.folderId }?.name ?: "Folder"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Text(title, color = Warm.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        for (t in activeTags) ChipButton("$t  ✕", true, onClearTags)
    }
}

@Composable
private fun EmptyState(section: LibrarySection, filtered: Boolean, noNotebooksAtAll: Boolean, onNew: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            when {
                noNotebooksAtAll -> "Your library is empty"
                filtered -> "No notebook has all of those tags"
                section == LibrarySection.Starred -> "Nothing starred yet"
                section is LibrarySection.Folder -> "This folder is empty"
                else -> "No notebooks here"
            },
            color = Warm.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium
        )
        Text(
            if (noNotebooksAtAll) "Create a notebook, or import a PDF textbook to write on top of it." else "Try another section, or create a notebook here.",
            color = Warm.TextMuted, fontSize = 13.sp
        )
        ChipButton("New notebook", true, onNew)
    }
}

@Composable
private fun NotebookGrid(
    cards: List<NotebookCard>,
    folderNames: Map<String, String>,
    onOpen: (String, Int) -> Unit,
    onAction: (Dlg) -> Unit,
    onStar: (NotebookCard) -> Unit,
    onCreate: () -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "create") { CreateCard(onCreate) }
        items(cards, key = { it.entity.notebookId }) { card ->
            NotebookCardView(card, folderNames[card.entity.folderId], { onOpen(card.entity.notebookId, 0) }, onAction, onStar)
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchState,
    folderNames: Map<String, String>,
    onOpen: (String, Int) -> Unit,
    onAction: (Dlg) -> Unit,
    onStar: (NotebookCard) -> Unit
) {
    if (state.notebooks.isEmpty() && state.pages.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing found for “${state.query}”", color = Warm.Text, fontSize = 18.sp)
            Text("Handwriting is searchable once a page has been written on and left.", color = Warm.TextMuted, fontSize = 13.sp)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (state.notebooks.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Notebooks") }
            items(state.notebooks, key = { "n-" + it.entity.notebookId }) { card ->
                NotebookCardView(card, folderNames[card.entity.folderId], { onOpen(card.entity.notebookId, 0) }, onAction, onStar)
            }
        }
        if (state.pages.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Pages  (${state.pages.size})") }
            items(state.pages, key = { "p-" + it.pageId }, span = { GridItemSpan(maxLineSpan) }) { hit ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(16.dp), elevation = 0.dp)
                        .clickable { onOpen(hit.notebookId, hit.pageIndex) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(hit.notebookTitle, color = Warm.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("Page ${hit.pageIndex + 1}", color = Warm.Accent, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun NotebookCardView(
    card: NotebookCard,
    folderName: String?,
    onOpen: () -> Unit,
    onAction: (Dlg) -> Unit,
    onStar: (NotebookCard) -> Unit
) {
    val e = card.entity
    var menu by remember { mutableStateOf(false) }
    val cover = Color(e.coverColorArgb)
    val accent = Color(e.coverAccentArgb)
    val artwork = rememberCoverBitmap(e.coverImageUri)
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Portrait cover: the artwork when there is one, otherwise the solid colour with its spine.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .shadowCard()
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(cover, cover.copy(red = cover.red * 0.7f, green = cover.green * 0.7f, blue = cover.blue * 0.7f))))
        ) {
            if (artwork != null) {
                androidx.compose.foundation.Image(
                    artwork, e.title, Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxHeight().width(10.dp).background(accent))
                Text(
                    e.title, color = Color(0xFFCBD5E1), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 4,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(start = 20.dp, end = 12.dp)
                )
            }
            if (card.isPdf) {
                Text(
                    "PDF", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .background(Color(0xCC11192B), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Row(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                Box(
                    Modifier.size(36.dp).padding(3.dp).background(Color(0x8811192B), CircleShape).clickable { onStar(card) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (e.isStarred) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Star", tint = if (e.isStarred) Color(0xFFFFD166) else Color.White, modifier = Modifier.size(18.dp))
                }
                Box {
                    Box(
                        Modifier.size(36.dp).padding(3.dp).background(Color(0x8811192B), CircleShape).clickable { menu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.MoreVert, "Options", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Open") }, { menu = false; onOpen() })
                        DropdownMenuItem({ Text("Rename") }, { menu = false; onAction(Dlg.RenameNotebook(card)) })
                        DropdownMenuItem({ Text("Tags…") }, { menu = false; onAction(Dlg.Tags(card)) })
                        DropdownMenuItem({ Text("Move to folder…") }, { menu = false; onAction(Dlg.MoveNotebook(card)) })
                        DropdownMenuItem({ Text("Delete", color = Warm.Danger) }, { menu = false; onAction(Dlg.DeleteNotebook(card)) })
                    }
                }
            }
        }
        // Title and date sit under the cover.
        Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(e.title, color = Warm.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(DateUtils.formatDateTime(null, e.updatedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR))
                    append("  ·  ").append(if (e.pageCount == 1) "1 page" else "${e.pageCount} pages")
                    if (folderName != null) append("  ·  ").append(folderName)
                },
                color = Warm.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (card.tags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in card.tags) Text(t, color = Warm.Accent, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun Modifier.shadowCard(): Modifier = this.shadow(8.dp, RoundedCornerShape(14.dp), clip = false)

/** All / Note / PDF filter tabs above the grid. */
private enum class LibraryKind(val label: String) { ALL("ALL"), NOTE("NOTE"), PDF("PDF") }

@Composable
private fun KindTabs(selected: LibraryKind, onSelect: (LibraryKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        for (k in LibraryKind.values()) {
            val on = k == selected
            Column(Modifier.clickable { onSelect(k) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(k.label, color = if (on) Warm.Text else Warm.TextMuted, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, letterSpacing = 1.sp)
                Box(Modifier.padding(top = 4.dp).height(2.dp).width(24.dp).background(if (on) Warm.Accent else Color.Transparent, RoundedCornerShape(1.dp)))
            }
        }
    }
}

/** The dashed "+ Create" tile that leads the grid. */
@Composable
private fun CreateCard(onCreate: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onCreate), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x1A3B82F6))
                .drawBehind {
                    drawRoundRect(
                        color = Color(0xFF3B82F6),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 1.6.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                        )
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Add, "Create", tint = Warm.Accent, modifier = Modifier.size(34.dp))
                Text("Create", color = Warm.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("New notebook", color = Warm.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/** Night-mountain vector scene that sits at the bottom of the navy sidebar. */
@Composable
private fun NightMountains(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val band = minOf(h, 190.dp.toPx())
        val top = h - band
        // moon
        drawCircle(Color(0x33DCE6FF), 16.dp.toPx(), Offset(w * 0.76f, top + band * 0.18f))
        drawCircle(Color(0x66DCE6FF), 9.dp.toPx(), Offset(w * 0.76f, top + band * 0.18f))
        // a few stars
        for ((fx, fy) in listOf(0.14f to 0.12f, 0.34f to 0.06f, 0.52f to 0.2f, 0.9f to 0.34f, 0.24f to 0.3f)) {
            drawCircle(Color(0x88FFFFFF), 1.3.dp.toPx(), Offset(w * fx, top + band * fy))
        }
        fun ridge(color: Color, pts: List<Pair<Float, Float>>) {
            val path = Path().apply {
                moveTo(0f, h)
                for ((fx, fy) in pts) lineTo(w * fx, top + band * fy)
                lineTo(w, h)
                close()
            }
            drawPath(path, color)
        }
        ridge(Color(0xFF1A2742), listOf(0f to 0.62f, 0.18f to 0.38f, 0.34f to 0.58f, 0.52f to 0.3f, 0.72f to 0.6f, 0.88f to 0.45f, 1f to 0.58f))
        ridge(Color(0xFF16213A), listOf(0f to 0.8f, 0.22f to 0.58f, 0.4f to 0.78f, 0.62f to 0.5f, 0.82f to 0.76f, 1f to 0.66f))
        ridge(Color(0xFF131C31), listOf(0f to 0.94f, 0.3f to 0.78f, 0.55f to 0.92f, 0.8f to 0.8f, 1f to 0.92f))
    }
}

