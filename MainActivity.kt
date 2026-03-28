@file:OptIn(ExperimentalMaterial3Api::class)

package com.bypass.innernode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bypass.innernode.ui.theme.InnerNodeTheme

// ── Палитра ──────────────────────────────────────────────────────────────────
private val Bg      = Color(0xFF0D1117)
private val BgCard  = Color(0xFF161B22)
private val BgEl    = Color(0xFF1C2333)
private val Primary = Color(0xFF4C9EFF)
private val Success = Color(0xFF3EE09A)
private val Warning = Color(0xFFFFB020)
private val Danger  = Color(0xFFFF4560)
private val TextPri = Color(0xFFF0F6FC)
private val TextSec = Color(0xFF8B949E)
private val Div     = Color(0xFF21262D)

class MainActivity : ComponentActivity() {
    private lateinit var vm: RoomsViewModel

    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) vm.connect()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent {
            InnerNodeTheme {
                val model: RoomsViewModel = viewModel(factory = RoomsViewModelFactory(application))
                vm = model
                AppRoot(model) { vpnLauncher.launch(it) }
            }
        }
    }
}

// ── Root ─────────────────────────────────────────────────────────────────────
@Composable
fun AppRoot(vm: RoomsViewModel, onVpnPerm: (Intent) -> Unit) {
    val ctx        = LocalContext.current
    var showRooms  by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var pwRoom     by remember { mutableStateOf<Room?>(null) }
    var showKillDialog by remember { mutableStateOf(false) }
    val snack      = remember { SnackbarHostState() }
    val error      by vm.errorMessage.collectAsState()

    LaunchedEffect(error) {
        error?.let { snack.showSnackbar(it, duration = SnackbarDuration.Short); vm.clearError() }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = {
            SnackbarHost(snack) { d ->
                Snackbar(d, containerColor = BgEl, contentColor = TextPri,
                    shape = RoundedCornerShape(12.dp))
            }
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopBar(
                showKill = vm.vpnState == RoomsViewModel.VpnState.CONNECTED,
                onKill   = { showKillDialog = true }
            )
            Spacer(Modifier.height(40.dp))
            PowerSection(vm) {
                val i = VpnService.prepare(ctx)
                if (i != null) onVpnPerm(i) else vm.connect()
            }
            Spacer(Modifier.height(40.dp))

            when {
                vm.vpnState != RoomsViewModel.VpnState.CONNECTED -> Unit
                vm.currentRoomName != null -> RoomSession(vm)
                else -> LobbyPanel(vm,
                    onJoin   = { showRooms = true },
                    onCreate = { showCreate = true }
                )
            }
        }
    }

    // ── Диалог полного выхода ──
    if (showKillDialog) {
        AlertDialog(
            onDismissRequest = { showKillDialog = false },
            containerColor = BgCard,
            shape = RoundedCornerShape(18.dp),
            title = { Text("Выйти из InnerNode?", color = TextPri, fontWeight = FontWeight.Bold) },
            text  = { Text("Туннель будет закрыт, уведомление исчезнет.", color = TextSec, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = { vm.killService(); showKillDialog = false },
                    colors  = ButtonDefaults.buttonColors(containerColor = Danger),
                    shape   = RoundedCornerShape(10.dp)
                ) { Text("Выйти", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showKillDialog = false }) {
                    Text("Отмена", color = TextSec)
                }
            }
        )
    }

    if (showRooms) {
        ModalBottomSheet(
            onDismissRequest = { showRooms = false },
            containerColor   = BgCard,
            shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = {
                Box(Modifier.padding(vertical = 12.dp).width(36.dp).height(4.dp)
                    .background(Div, RoundedCornerShape(2.dp)))
            }
        ) {
            RoomsSheet(vm, onSelect = { room ->
                if (room.isPrivate) pwRoom = room
                else { vm.joinRoom(room.name, null); showRooms = false }
            }, onDismiss = { showRooms = false })
        }
    }

    if (showCreate) {
        CreateRoomDialog(
            onDismiss = { showCreate = false },
            onCreate  = { n, p -> vm.createRoom(n, p); showCreate = false }
        )
    }

    pwRoom?.let { room ->
        PasswordDialog(
            roomName  = room.name,
            onDismiss = { pwRoom = null },
            onConfirm = { p -> vm.joinRoom(room.name, p); pwRoom = null; showRooms = false }
        )
    }
}

// ── Шапка ────────────────────────────────────────────────────────────────────
@Composable
fun TopBar(showKill: Boolean, onKill: () -> Unit) {
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("InnerNode", color = TextPri, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Кнопка полного выхода — только когда подключены
            if (showKill) {
                Box(
                    Modifier.size(36.dp)
                        .background(Danger.copy(0.12f), RoundedCornerShape(10.dp))
                        .border(1.dp, Danger.copy(0.3f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onKill),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PowerOff, null, tint = Danger, modifier = Modifier.size(17.dp))
                }
            }
            Box(
                Modifier.size(36.dp).background(BgEl, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Settings, null, tint = TextSec, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Кнопка питания ───────────────────────────────────────────────────────────
@Composable
fun PowerSection(vm: RoomsViewModel, onToggle: () -> Unit) {
    val isConn    = vm.vpnState == RoomsViewModel.VpnState.CONNECTED
    val isConning = vm.vpnState == RoomsViewModel.VpnState.CONNECTING

    val ringColor = when (vm.vpnState) {
        RoomsViewModel.VpnState.CONNECTED    -> Success
        RoomsViewModel.VpnState.CONNECTING   -> Warning
        RoomsViewModel.VpnState.DISCONNECTED -> Div
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = when (vm.vpnState) {
                RoomsViewModel.VpnState.CONNECTED    -> "Подключено"
                RoomsViewModel.VpnState.CONNECTING   -> "Подключение..."
                RoomsViewModel.VpnState.DISCONNECTED -> "Не подключено"
            },
            color = ringColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
        )

        if (vm.currentRoomName != null) {
            Spacer(Modifier.height(4.dp))
            Text("Комната: ${vm.currentRoomName}", color = TextSec, fontSize = 13.sp)
        }
        vm.myIp?.let { ip ->
            Spacer(Modifier.height(8.dp))
            IpChip(ip)
        }

        Spacer(Modifier.height(28.dp))

        Box(
            Modifier
                .size(140.dp)
                .background(
                    if (isConn) Brush.radialGradient(listOf(Color(0xFF1A3D2B), Color(0xFF0F2218)))
                    else Brush.radialGradient(listOf(BgEl, BgCard)),
                    CircleShape
                )
                .border(2.dp, ringColor, CircleShape)
                .clip(CircleShape)
                .clickable(enabled = !isConning) {
                    if (isConn) vm.disconnect() else onToggle()
                },
            contentAlignment = Alignment.Center
        ) {
            if (isConning) {
                CircularProgressIndicator(color = Warning, modifier = Modifier.size(40.dp), strokeWidth = 2.5.dp)
            } else {
                Icon(Icons.Rounded.PowerSettingsNew, null,
                    tint = if (isConn) Success else TextSec,
                    modifier = Modifier.size(56.dp))
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            if (isConn) "Нажмите для отключения" else "Нажмите для подключения",
            color = TextSec, fontSize = 12.sp
        )
    }
}

// ── IP чип ───────────────────────────────────────────────────────────────────
@Composable
fun IpChip(ip: String) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .background(BgEl, RoundedCornerShape(20.dp))
            .border(1.dp, Div, RoundedCornerShape(20.dp))
            .clickable {
                val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("IP", ip))
                Toast.makeText(ctx, "Скопировано: $ip", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(Success, CircleShape))
        Text(ip, color = TextPri, fontFamily = FontFamily.Monospace,
            fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Icon(Icons.Rounded.ContentCopy, null, tint = TextSec, modifier = Modifier.size(13.dp))
    }
}

// ── Лобби ────────────────────────────────────────────────────────────────────
@Composable
fun LobbyPanel(vm: RoomsViewModel, onJoin: () -> Unit, onCreate: () -> Unit) {
    val rooms by vm.rooms.collectAsState()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().height(54.dp)
                .background(Primary, RoundedCornerShape(14.dp))
                .clickable(onClick = onJoin),
            contentAlignment = Alignment.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MeetingRoom, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text("Войти в комнату", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Box(
            Modifier.fillMaxWidth().height(54.dp)
                .background(BgCard, RoundedCornerShape(14.dp))
                .border(1.dp, Div, RoundedCornerShape(14.dp))
                .clickable(onClick = onCreate),
            contentAlignment = Alignment.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, null, tint = Primary, modifier = Modifier.size(18.dp))
                Text("Создать комнату", color = TextPri, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (rooms.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Активные комнаты", color = TextPri, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("${rooms.size}", color = Primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(4.dp))
            rooms.take(3).forEach { room -> SmallRoomCard(room) { onJoin() } }
            if (rooms.size > 3) {
                TextButton(onClick = onJoin, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Показать все ${rooms.size}", color = Primary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun SmallRoomCard(room: Room, onClick: () -> Unit) {
    val fill     = room.users.toFloat() / room.max
    val barColor = when { fill > 0.8f -> Danger; fill > 0.5f -> Warning; else -> Success }

    Row(
        Modifier.fillMaxWidth()
            .background(BgCard, RoundedCornerShape(12.dp))
            .border(1.dp, Div, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (room.isPrivate) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                null, tint = if (room.isPrivate) Warning else Success,
                modifier = Modifier.size(15.dp)
            )
            Text(room.name, color = TextPri, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp).height(3.dp).background(Div, RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(barColor, RoundedCornerShape(2.dp)))
            }
            Text("${room.users}/${room.max}", color = TextSec,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

// ── Сессия в комнате ──────────────────────────────────────────────────────────
@Composable
fun RoomSession(vm: RoomsViewModel) {
    val peers = vm.currentPeers.collectAsState().value
    val ctx   = LocalContext.current

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Активная комната", color = TextSec, fontSize = 12.sp)
                Text(vm.currentRoomName ?: "", color = TextPri,
                    fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Box(
                Modifier
                    .background(Danger.copy(0.12f), RoundedCornerShape(10.dp))
                    .border(1.dp, Danger.copy(0.3f), RoundedCornerShape(10.dp))
                    .clickable { vm.leaveCurrentRoom() }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text("Выйти", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(
            Modifier.fillMaxWidth()
                .background(BgCard, RoundedCornerShape(14.dp))
                .border(1.dp, Div, RoundedCornerShape(14.dp))
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(BgEl, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("IP-адрес", color = TextSec, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Text("Роль", color = TextSec, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.width(60.dp))
                Spacer(Modifier.width(80.dp))
            }

            if (peers.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text("Загрузка...", color = TextSec, fontSize = 13.sp)
                }
            } else {
                peers.forEachIndexed { idx, peer ->
                    val isHost = peer.role == "host"
                    val isMe   = peer.ip == vm.myIp
                    if (idx > 0) HorizontalDivider(color = Div, thickness = 1.dp)
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (isMe) Primary.copy(0.05f) else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(7.dp).background(
                                if (isHost) Success else if (isMe) Primary else TextSec,
                                CircleShape
                            ))
                            Text(
                                peer.ip + if (isMe) " (я)" else "",
                                color = if (isMe) Primary else TextPri,
                                fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                                fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                        Box(Modifier.width(60.dp)) {
                            Box(
                                Modifier.background(
                                    if (isHost) Success.copy(0.15f) else BgEl,
                                    RoundedCornerShape(6.dp)
                                ).padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(if (isHost) "HOST" else "NODE",
                                    color = if (isHost) Success else TextSec,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Box(
                            Modifier
                                .background(Primary.copy(0.1f), RoundedCornerShape(8.dp))
                                .border(1.dp, Primary.copy(0.2f), RoundedCornerShape(8.dp))
                                .clickable {
                                    val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clip.setPrimaryClip(ClipData.newPlainText("IP", peer.ip))
                                    Toast.makeText(ctx, "Скопировано: ${peer.ip}", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.ContentCopy, null, tint = Primary, modifier = Modifier.size(12.dp))
                                Text("IP", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Bottom sheet ─────────────────────────────────────────────────────────────
@Composable
fun RoomsSheet(vm: RoomsViewModel, onSelect: (Room) -> Unit, onDismiss: () -> Unit) {
    val rooms by vm.rooms.collectAsState()

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Комнаты", color = TextPri, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("${rooms.size} онлайн", color = Primary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))

        if (rooms.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.SearchOff, null, tint = TextSec, modifier = Modifier.size(36.dp))
                    Text("Нет активных комнат", color = TextSec, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rooms) { room -> RoomListCard(room) { onSelect(room) } }
            }
        }
    }
}

@Composable
fun RoomListCard(room: Room, onClick: () -> Unit) {
    val fill     = room.users.toFloat() / room.max
    val barColor = when { fill > 0.8f -> Danger; fill > 0.5f -> Warning; else -> Success }

    Row(
        Modifier.fillMaxWidth()
            .background(BgEl, RoundedCornerShape(14.dp))
            .border(1.dp, Div, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(
                    if (room.isPrivate) Warning.copy(0.12f) else Success.copy(0.1f),
                    RoundedCornerShape(10.dp)
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (room.isPrivate) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    null, tint = if (room.isPrivate) Warning else Success,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(room.name, color = TextPri, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(50.dp).height(3.dp).background(BgCard, RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(barColor, RoundedCornerShape(2.dp)))
                    }
                    Text("${room.users} / ${room.max}", color = TextSec, fontSize = 12.sp)
                }
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = TextSec, modifier = Modifier.size(18.dp))
    }
}

// ── Диалоги ──────────────────────────────────────────────────────────────────
@Composable
fun CreateRoomDialog(onDismiss: () -> Unit, onCreate: (String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCard, shape = RoundedCornerShape(20.dp),
        title = { Text("Новая комната", color = TextPri, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppField(name, { name = it }, "Название")
                AppField(pass, { pass = it }, "Пароль (необязательно)")
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onCreate(name.trim(), pass.ifBlank { null }) },
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Создать", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) } }
    )
}

@Composable
fun PasswordDialog(roomName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pass by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCard, shape = RoundedCornerShape(20.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Приватная", color = Warning, fontSize = 12.sp)
                Text(roomName, color = TextPri, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = { AppField(pass, { pass = it }, "Пароль") },
        confirmButton = {
            Button(onClick = { if (pass.isNotBlank()) onConfirm(pass) },
                colors = ButtonDefaults.buttonColors(containerColor = Warning),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Войти", color = Color.Black, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) } }
    )
}

@Composable
fun AppField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPri, unfocusedTextColor = TextPri,
            focusedBorderColor = Primary.copy(0.7f), unfocusedBorderColor = Div,
            focusedLabelColor = Primary, unfocusedLabelColor = TextSec,
            cursorColor = Primary,
            focusedContainerColor = BgEl, unfocusedContainerColor = BgEl
        )
    )
}