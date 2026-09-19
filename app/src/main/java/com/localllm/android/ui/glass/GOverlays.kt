package com.localllm.android.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** Full-screen scaffold replacing M3 Scaffold (no insets magic; callers own padding). */
@Composable
fun GScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxSize().background(GlassTheme.colors.background)) {
        topBar()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
        bottomBar()
    }
}

/** 56dp top bar replacing M3 TopAppBar. */
@Composable
fun GTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) { navigationIcon() }
        Box(modifier = Modifier.weight(1f)) { title() }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Dialog replacing M3 AlertDialog. */
@Composable
fun GDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismissRequest) {
        GCard(modifier = modifier.fillMaxWidth(), cornerRadius = 22.dp) {
            Column(modifier = Modifier.padding(22.dp)) {
                title?.let {
                    it()
                    Spacer(modifier = Modifier.height(12.dp))
                }
                text?.let { it() }
                if (confirmButton != null || dismissButton != null) {
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dismissButton?.let { it() }
                        if (confirmButton != null && dismissButton != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        confirmButton?.let { it() }
                    }
                }
            }
        }
    }
}

/** Popup menu replacing M3 DropdownMenu. */
@Composable
fun GMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded) return
    Popup(
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        GCard(modifier = modifier, cornerRadius = 16.dp) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                content = content
            )
        }
    }
}

@Composable
fun GMenuItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingIcon?.let {
            it()
            Spacer(modifier = Modifier.width(12.dp))
        }
        Box(modifier = Modifier.weight(1f)) { text() }
        trailingIcon?.let {
            Spacer(modifier = Modifier.width(12.dp))
            it()
        }
    }
}

/** Bottom sheet replacing M3 ModalBottomSheet. */
@Composable
fun GSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!visible) return
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onDismissRequest)
            )
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                        .background(GlassTheme.colors.surface.copy(alpha = 0.92f))
                        .navigationBarsPadding()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        content = content
                    )
                }
            }
        }
    }
}

/**
 * Side drawer replacing M3 ModalNavigationDrawer (open state hoisted by caller;
 * no swipe gesture — opened via hamburger, closed via scrim tap or selection).
 */
@Composable
fun GDrawer(
    open: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    drawerContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onClose)
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it }
        ) {
            Box(modifier = Modifier.fillMaxHeight()) {
                drawerContent()
            }
        }
    }
}
