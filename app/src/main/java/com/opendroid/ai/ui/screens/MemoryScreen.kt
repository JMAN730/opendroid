package com.opendroid.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.Macro
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.MemoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(MemoryType.SEMANTIC) }
    var searchQuery by remember { mutableStateOf("") }
    var isAddingFact by remember { mutableStateOf(false) }
    
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "PERSISTENT MEMORY",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AccentNeonGreen,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp
                    )
                },
                actions = {
                    TextButton(onClick = { viewModel.clearMemories(selectedTab) }) {
                        Text("Wipe Category", color = AccentRed, fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Memory Category Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = DarkBackground,
                contentColor = AccentNeonGreen,
                edgePadding = 0.dp,
                divider = { Divider(color = BorderColor) }
            ) {
                MemoryType.values().forEach { type ->
                    Tab(
                        selected = selectedTab == type,
                        onClick = { 
                            selectedTab = type
                            searchQuery = "" // Reset search query when changing tabs
                            isAddingFact = false
                        },
                        text = {
                            Text(
                                text = type.name,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (selectedTab == type) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Search Bar & Add Button (Conditional)
            if (selectedTab != MemoryType.WORKING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            val hint = when (selectedTab) {
                                MemoryType.EPISODIC -> "Search conversation logs..."
                                MemoryType.PROCEDURAL -> "Search macros..."
                                else -> "Search facts..."
                            }
                            Text(hint, color = TextSecondary, fontSize = 13.sp)
                        },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentNeonGreen,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (selectedTab == MemoryType.SEMANTIC) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { isAddingFact = !isAddingFact },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentNeonGreen)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Memory", tint = DarkBackground)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Render Dynamic Tab Contents
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    MemoryType.WORKING -> {
                        WorkingMemoryView(viewModel = viewModel)
                    }
                    MemoryType.EPISODIC -> {
                        EpisodicMemoryView(viewModel = viewModel, searchQuery = searchQuery)
                    }
                    MemoryType.SEMANTIC -> {
                        SemanticMemoryView(
                            viewModel = viewModel,
                            searchQuery = searchQuery,
                            isAddingFact = isAddingFact,
                            onIsAddingFactChange = { isAddingFact = it },
                            newKey = newKey,
                            onNewKeyChange = { newKey = it },
                            newValue = newValue,
                            onNewValueChange = { newValue = it }
                        )
                    }
                    MemoryType.PROCEDURAL -> {
                        ProceduralMemoryView(viewModel = viewModel, searchQuery = searchQuery)
                    }
                }
            }
        }
    }
}

@Composable
fun WorkingMemoryView(viewModel: MemoryViewModel) {
    val activePlan by viewModel.activePlan.collectAsState()
    val workingMemory = viewModel.workingMemory
    
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. Device State Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ACTIVE ENVIRONMENT STATE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentCyan,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StateItem("Battery Level", "${workingMemory.batteryLevel}%", AccentNeonGreen)
                        StateItem("WiFi State", workingMemory.wifiState, if (workingMemory.wifiState == "Active") AccentNeonGreen else if (workingMemory.wifiState == "Inactive") AccentRed else TextSecondary)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StateItem("Connectivity", workingMemory.connectivity, AccentCyan)
                        StateItem("Internet", if (workingMemory.isInternetAvailable) "Available" else "NOT AVAILABLE", if (workingMemory.isInternetAvailable) AccentNeonGreen else AccentRed)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StateItem("Location Context", workingMemory.locationContext, TextSecondary)
                    }
                }
            }
        }

        // 2. Active Plan Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ACTIVE PLAN MONITOR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentCyan,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val plan = activePlan
                    if (plan != null) {
                        Text(
                            text = plan.goal,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = when (plan.status.name) {
                                    "RUNNING" -> AccentCyan.copy(alpha = 0.2f)
                                    "COMPLETED" -> AccentNeonGreen.copy(alpha = 0.2f)
                                    else -> AccentRed.copy(alpha = 0.2f)
                                },
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Text(
                                    text = plan.status.name,
                                    color = when (plan.status.name) {
                                        "RUNNING" -> AccentCyan
                                        "COMPLETED" -> AccentNeonGreen
                                        else -> AccentRed
                                    },
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider(color = BorderColor)
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        plan.steps.forEachIndexed { index, step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = when (step.status.name) {
                                        "COMPLETED" -> "●"
                                        "RUNNING" -> "▶"
                                        "FAILED" -> "✖"
                                        else -> "○"
                                    },
                                    color = when (step.status.name) {
                                        "COMPLETED" -> AccentNeonGreen
                                        "RUNNING" -> AccentCyan
                                        "FAILED" -> AccentRed
                                        else -> TextSecondary
                                    },
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(end = 8.dp, top = 2.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${index + 1}. ${step.description}",
                                        fontSize = 12.sp,
                                        color = if (step.status.name == "COMPLETED") TextSecondary else TextPrimary,
                                        fontWeight = if (step.status.name == "RUNNING") FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (!step.result.isNullOrBlank()) {
                                        Text(
                                            text = "Result: ${step.result}",
                                            fontSize = 10.sp,
                                            color = AccentCyan,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    if (!step.error.isNullOrBlank()) {
                                        Text(
                                            text = "Error: ${step.error}",
                                            fontSize = 10.sp,
                                            color = AccentRed,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No active autonomous plan running.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // 3. Current Session history
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "WORKING SESSION HISTORY (LAST 20)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentCyan,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val history = workingMemory.conversationHistory
                    if (history.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            history.forEach { msg ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (msg.sender.name == "USER") Arrangement.End else Arrangement.Start
                                ) {
                                    Surface(
                                        color = if (msg.sender.name == "USER") AccentCyan.copy(alpha = 0.15f) else AccentNeonGreen.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, if (msg.sender.name == "USER") AccentCyan.copy(alpha = 0.3f) else AccentNeonGreen.copy(alpha = 0.2f))
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = if (msg.sender.name == "USER") "USER" else "AGENT",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = if (msg.sender.name == "USER") AccentCyan else AccentNeonGreen
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = msg.text,
                                                fontSize = 12.sp,
                                                color = TextPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No messages in current working session.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StateItem(label: String, value: String, valueColor: Color) {
    Column {
        Text(text = label, color = TextSecondary, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun EpisodicMemoryView(viewModel: MemoryViewModel, searchQuery: String) {
    val conversations by viewModel.conversationHistory.collectAsState()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    val filteredLogs = conversations.filter {
        it.text.contains(searchQuery, ignoreCase = true) ||
        (it.modelBadge?.contains(searchQuery, ignoreCase = true) ?: false)
    }

    if (filteredLogs.isNotEmpty()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredLogs) { log ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (log.sender.name == "USER") "USER" else "AGENT",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (log.sender.name == "USER") AccentCyan else AccentNeonGreen
                                )
                                log.modelBadge?.let { badge ->
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = AccentCyan.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = badge,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = AccentCyan,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = dateFormat.format(Date(log.timestamp)),
                                fontSize = 9.sp,
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = log.text,
                            fontSize = 13.sp,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No episodic chat logs recorded.",
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun SemanticMemoryView(
    viewModel: MemoryViewModel,
    searchQuery: String,
    isAddingFact: Boolean,
    onIsAddingFactChange: (Boolean) -> Unit,
    newKey: String,
    onNewKeyChange: (String) -> Unit,
    newValue: String,
    onNewValueChange: (String) -> Unit
) {
    val allMemories by viewModel.memoriesList.collectAsState()
    
    val filteredMemories = allMemories.filter {
        it.type == MemoryType.SEMANTIC && (
            it.key.contains(searchQuery, ignoreCase = true) ||
            it.value.contains(searchQuery, ignoreCase = true)
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = isAddingFact) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "STORE NEW MEMORY FACT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentCyan
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newKey,
                        onValueChange = onNewKeyChange,
                        label = { Text("Fact Key/Identifier", fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentNeonGreen,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = onNewValueChange,
                        label = { Text("Fact Content/Details", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentNeonGreen,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onIsAddingFactChange(false) }) {
                            Text("Cancel", color = AccentRed)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newKey.isNotBlank() && newValue.isNotBlank()) {
                                    viewModel.storeFact(newKey, newValue, MemoryType.SEMANTIC)
                                    onNewKeyChange("")
                                    onNewValueChange("")
                                    onIsAddingFactChange(false)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentNeonGreen, contentColor = DarkBackground),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Save Fact", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filteredMemories.isNotEmpty()) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredMemories) { mem ->
                    MemoryItemCard(
                        memory = mem,
                        onDelete = { viewModel.deleteMemory(mem.key) }
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No semantic facts indexed in this category.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun MemoryItemCard(
    memory: Memory,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = memory.key,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentNeonGreen,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = memory.value,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Indexed: ${dateFormat.format(Date(memory.timestamp))}",
                    fontSize = 9.sp,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Memory",
                    tint = TextSecondary.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun ProceduralMemoryView(viewModel: MemoryViewModel, searchQuery: String) {
    val macros by viewModel.macrosList.collectAsState()

    val filteredMacros = macros.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
        it.trigger.contains(searchQuery, ignoreCase = true)
    }

    if (filteredMacros.isNotEmpty()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredMacros) { macro ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = macro.name.uppercase(),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentNeonGreen,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = if (macro.isSystem) AccentCyan.copy(alpha = 0.15f) else TextSecondary.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = if (macro.isSystem) "SYSTEM" else "USER",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (macro.isSystem) AccentCyan else TextSecondary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Trigger: \"${macro.trigger}\"",
                                fontSize = 12.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "PROCEDURAL ACTIONS:",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            macro.steps.forEachIndexed { index, step ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "  → ",
                                        fontSize = 11.sp,
                                        color = AccentCyan,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = step.description,
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                        
                        if (!macro.isSystem) {
                            IconButton(onClick = { viewModel.deleteMacro(macro.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Macro",
                                    tint = AccentRed.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No custom macros or procedures registered.",
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
