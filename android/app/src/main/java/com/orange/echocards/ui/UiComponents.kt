package com.orange.echocards.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Bg = Color(0xFFF7F8F8)
internal val Surface = Color.White
internal val Ink = Color(0xFF101314)
internal val Muted = Color(0xFF6B7376)
internal val Border = Color(0xFFE7EAEA)
internal val TealDeep = Color(0xFF0A7373)
internal val Danger = Color(0xFFB93434)
internal val FieldBg = Color(0xFFFBFCFC)

@Composable
internal fun TopBar(title: String? = null, back: (() -> Unit)? = null, trailing: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) Text("‹", Modifier.size(48.dp).clickable(onClick = back), fontSize = 36.sp,
            color = Ink, textAlign = TextAlign.Center)
        if (title != null) Text(title, Modifier.padding(start = if (back == null) 12.dp else 0.dp),
            fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text("✎", Modifier.size(48.dp).clickable(onClick = trailing).padding(top = 10.dp),
            fontSize = 23.sp, color = Ink, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun ActionButton(label: String, onClick: () -> Unit, primary: Boolean = true, height: Dp = 48.dp,
    modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp))
        .background(if (primary) TealDeep else Surface)
        .border(if (primary) 0.dp else 1.dp, if (primary) TealDeep else Color(0xFFD9DEDE), RoundedCornerShape(14.dp))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) Surface else Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
internal fun BottomTabs(home: Boolean, onHome: () -> Unit, onMine: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(80.dp).background(Surface).border(0.5.dp, Border)
        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tab("⌂", "首页", home, Modifier.weight(1f), onHome)
        Tab("♙", "我的", !home, Modifier.weight(1f), onMine)
    }
}

@Composable
private fun Tab(icon: String, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(icon, fontSize = 23.sp, color = if (selected) TealDeep else Muted, lineHeight = 23.sp)
        Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) TealDeep else Muted)
    }
}

@Composable
internal fun MiniStack() {
    Box(Modifier.size(width = 32.dp, height = 44.dp)) {
        Box(Modifier.padding(start = 8.dp, top = 11.dp).size(15.dp).rotate(-45f)
            .clip(RoundedCornerShape(4.dp)).background(FieldBg).border(1.dp, Border, RoundedCornerShape(4.dp)))
        Box(Modifier.size(width = 24.dp, height = 34.dp).clip(RoundedCornerShape(6.dp)).background(Surface)
            .border(1.dp, Border, RoundedCornerShape(6.dp)))
    }
}

@Composable
internal fun FormField(label: String, value: String, onValue: (String) -> Unit,
    singleLine: Boolean = false, hint: String = "") {
    Column {
        Text(label, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(7.dp))
        BasicTextField(value, onValue, singleLine = singleLine,
            textStyle = TextStyle(fontSize = 15.sp, color = Ink),
            modifier = Modifier.fillMaxWidth().height(if (singleLine) 44.dp else 88.dp)
                .clip(RoundedCornerShape(12.dp)).background(FieldBg)
                .border(1.dp, Border, RoundedCornerShape(12.dp)).padding(12.dp),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = Muted, fontSize = 15.sp); inner() } })
    }
}

internal fun modeName(mode: String): String = when (mode) {
    "follow_along" -> "自动跟读"
    "auto_play" -> "自动播放"
    else -> "手动学习"
}
