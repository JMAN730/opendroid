package com.opendroid.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingUp
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
import com.opendroid.ai.core.memory.graph.KnowledgeCategory
import com.opendroid.ai.core.memory.graph.KnowledgeNode
import com.opendroid.ai.core.memory.graph.MemoryTier
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.Macro
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.MemoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MemoryScreenTab(val title: String) {
    GROWTH_GRAPH("GROWTH GRAPH"),
    SEMANTIC("LONG-TERM"),
    WORKING("TEMPORARY"),
    EPISODIC("EPISODIC"),
    PROCEDURAL("MACROS")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(MemoryScreenTab.GROWTH_GRAPH) }
    var searchQuery by remember { mutableStateOf("") }
    var isAddingFact by remember { mutableStateOf(false) }
    
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "PERSONAL MEMORY",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AccentNeonGreen,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp
                    )
                },
                actions = {
                    TextButton(onClick = {
                        when (selectedTab) {
                            MemoryScreenTab.GROWTH_GRAPH -> viewModel.clearMemoryTier(MemoryTier.LEARNED_PATTERN)
                            MemoryScreenTab.SEMANTIC -> viewModel.clearMemories(MemoryType.SEMANTIC)
                            MemoryScreenTab.WORKING -> viewModel.clearMemories(MemoryType.WORKING)
                            MemoryScreenTab.EPISODIC -> viewModel.clearMemories(MemoryType.EPISODIC)
                            MemoryScreenTab.PROCEDURAL -> viewModel.clearMemories(MemoryType.PROCEDURAL)
                        }
                    }) {
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
                MemoryScreenTab.values().forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { 
                            selectedTab = tab
                            searchQuery = "" // Reset search query when changing tabs
                            isAddingFact = false
                        },
                        text = {
                            Text(
                                text = tab.title,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Search Bar & Add Button (Conditional)
            if (selectedTab != MemoryScreenTab.WORKING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            val hint = when (selectedTab) {
                                MemoryScreenTab.GROWTH_GRAPH -> "Search Knowledge Graph..."
                                MemoryScreenTab.EPISODIC -> "Search conversation logs..."
                                MemoryScreenTab.PROCEDURAL -> "Search macros..."
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
                    if (selectedTab == MemoryScreenTab.SEMANTIC) {
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
                    MemoryScreenTab.GROWTH_GRAPH -> {
                        KnowledgeGraphView(viewModel = viewModel, searchQuery = searchQuery)
                    }
                    MemoryScreenTab.WORKING -> {
                        WorkingMemoryView(viewModel = viewModel)
                    }
                    MemoryScreenTab.EPISODIC -> {
                        EpisodicMemoryView(viewModel = viewModel, searchQuery = searchQuery)
                    }
                    MemoryScreenTab.SEMANTIC -> {
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
                    MemoryScreenTab.PROCEDURAL -> {
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

@Composable
fun KnowledgeGraphView(
    viewModel: MemoryViewModel,
    searchQuery: String
) {
    val graph by viewModel.knowledgeGraph.collectAsState()
    var selectedTierFilter by remember { mutableStateOf<MemoryTier?>(null) }
    var selectedCategoryFilter by remember { mutableStateOf<KnowledgeCategory?>(null) }
    var isAddingKnowledge by remember { mutableStateOf(false) }
    var addIsSensitive by remember { mutableStateOf(false) }
    var newLabel by remember { mutableStateOf("") }
    var newSummary by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf(KnowledgeCategory.USER_PREFERENCE) }

    val allNodes = graph.nodes.values.toList()
    val filteredNodes = allNodes.filter { node ->
        (selectedTierFilter == null || node.tier == selectedTierFilter) &&
        (selectedCategoryFilter == null || node.category == selectedCategoryFilter) &&
        (searchQuery.isBlank() ||
            node.label.contains(searchQuery, ignoreCase = true) ||
            node.summary.contains(searchQuery, ignoreCase = true) ||
            node.properties.values.any { it.contains(searchQuery, ignoreCase = true) }
        )
    }.sortedWith(
        compareBy<KnowledgeNode> {
            when (it.tier) {
                MemoryTier.LONG_TERM -> 0
                MemoryTier.LEARNED_PATTERN -> 1
                MemoryTier.SENSITIVE -> 2
                MemoryTier.TEMPORARY -> 3
            }
        }.thenByDescending { it.confidence }
         .thenByDescending { it.lastUpdated }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Tier Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = selectedTierFilter == null,
                    onClick = { selectedTierFilter = null },
                    label = { Text("All Levels (${allNodes.size})", fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentNeonGreen,
                        selectedLabelColor = DarkBackground,
                        containerColor = CardBackground,
                        labelColor = TextSecondary
                    )
                )
            }
            items(MemoryTier.values()) { tier ->
                val count = allNodes.count { it.tier == tier }
                val (label, icon) = when (tier) {
                    MemoryTier.TEMPORARY -> "Level 1: Temp" to "⚡"
                    MemoryTier.LONG_TERM -> "Level 2: Long-Term" to "🧠"
                    MemoryTier.LEARNED_PATTERN -> "Level 3: Patterns" to "📈"
                    MemoryTier.SENSITIVE -> "Level 4: Sensitive" to "🔒"
                }
                FilterChip(
                    selected = selectedTierFilter == tier,
                    onClick = { selectedTierFilter = if (selectedTierFilter == tier) null else tier },
                    label = { Text("$icon $label ($count)", fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (tier) {
                            MemoryTier.SENSITIVE -> AccentOrange
                            MemoryTier.LEARNED_PATTERN -> AccentCyan
                            else -> AccentNeonGreen
                        },
                        selectedLabelColor = DarkBackground,
                        containerColor = CardBackground,
                        labelColor = TextSecondary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Category Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = selectedCategoryFilter == null,
                    onClick = { selectedCategoryFilter = null },
                    label = { Text("All Categories", fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TextPrimary.copy(alpha = 0.2f),
                        selectedLabelColor = TextPrimary,
                        containerColor = CardBackground.copy(alpha = 0.6f),
                        labelColor = TextSecondary
                    )
                )
            }
            items(KnowledgeCategory.values()) { cat ->
                val icon = when (cat) {
                    KnowledgeCategory.CONTACT -> "👥"
                    KnowledgeCategory.TASK_ROUTINE -> "⚙️"
                    KnowledgeCategory.APP_PREFERENCE -> "📱"
                    KnowledgeCategory.SCHEDULE -> "📅"
                    KnowledgeCategory.PROJECT -> "📁"
                    KnowledgeCategory.RESOURCE -> "🌐"
                    KnowledgeCategory.NOTE_FACT -> "📝"
                    KnowledgeCategory.USER_PREFERENCE -> "⭐"
                }
                FilterChip(
                    selected = selectedCategoryFilter == cat,
                    onClick = { selectedCategoryFilter = if (selectedCategoryFilter == cat) null else cat },
                    label = { Text("$icon ${cat.name.replace('_', ' ')}", fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentCyan.copy(alpha = 0.3f),
                        selectedLabelColor = AccentCyan,
                        containerColor = CardBackground.copy(alpha = 0.6f),
                        labelColor = TextSecondary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Add Knowledge Header Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "KNOWLEDGE ENTITIES (${filteredNodes.size})",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = AccentCyan,
                letterSpacing = 1.sp
            )
            TextButton(onClick = { isAddingKnowledge = !isAddingKnowledge }) {
                Icon(Icons.Default.Add, contentDescription = null, tint = AccentNeonGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isAddingKnowledge) "Close" else "Add Entity / Secret", color = AccentNeonGreen, fontSize = 11.sp)
            }
        }

        // Add Knowledge / Secret Card
        AnimatedVisibility(visible = isAddingKnowledge) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = if (addIsSensitive) "ADD LEVEL 4 ENCRYPTED SECRET" else "ADD LEVEL 2 LONG-TERM KNOWLEDGE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (addIsSensitive) AccentOrange else AccentNeonGreen
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = !addIsSensitive,
                            onClick = { addIsSensitive = false },
                            label = { Text("🧠 Long-Term Memory", fontSize = 11.sp) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = addIsSensitive,
                            onClick = { addIsSensitive = true },
                            label = { Text("🔒 Keystore Encrypted", fontSize = 11.sp) }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text(if (addIsSensitive) "Secret Key / Label (e.g. locker_code)" else "Label / Title (e.g. Favorite Coffee)", fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (addIsSensitive) AccentOrange else AccentNeonGreen,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newSummary,
                        onValueChange = { newSummary = it },
                        label = { Text(if (addIsSensitive) "Secret Value (Hardware Encrypted)" else "Details / Description", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (addIsSensitive) AccentOrange else AccentNeonGreen,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { isAddingKnowledge = false }) {
                            Text("Cancel", color = AccentRed)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newLabel.isNotBlank() && newSummary.isNotBlank()) {
                                    if (addIsSensitive) {
                                        viewModel.recordSensitiveData(newLabel, newSummary, newLabel)
                                    } else {
                                        viewModel.recordExplicitKnowledge(newLabel, newSummary, newCategory)
                                    }
                                    newLabel = ""
                                    newSummary = ""
                                    isAddingKnowledge = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (addIsSensitive) AccentOrange else AccentNeonGreen,
                                contentColor = DarkBackground
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Save Entry", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (filteredNodes.isNotEmpty()) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredNodes, key = { it.id }) { node ->
                    KnowledgeNodeCard(
                        node = node,
                        onPromote = { viewModel.promotePattern(node.id) },
                        onDelete = { viewModel.deleteKnowledgeNode(node.id) }
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
                    text = "No Knowledge Graph entities matching filter.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun KnowledgeNodeCard(
    node: KnowledgeNode,
    onPromote: () -> Unit,
    onDelete: () -> Unit
) {
    val tierColor = when (node.tier) {
        MemoryTier.SENSITIVE -> AccentOrange
        MemoryTier.LEARNED_PATTERN -> AccentCyan
        MemoryTier.TEMPORARY -> TextSecondary
        MemoryTier.LONG_TERM -> AccentNeonGreen
    }
    val tierIcon = when (node.tier) {
        MemoryTier.SENSITIVE -> "🔒"
        MemoryTier.LEARNED_PATTERN -> "📈"
        MemoryTier.TEMPORARY -> "⚡"
        MemoryTier.LONG_TERM -> "🧠"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = tierColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, tierColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "$tierIcon ${node.tier.name}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = tierColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        color = TextPrimary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = node.category.name.replace('_', ' '),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (node.tier == MemoryTier.LEARNED_PATTERN) {
                        Text(
                            text = "${(node.confidence * 100).toInt()}% conf",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AccentCyan
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Node",
                            tint = TextSecondary.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = node.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AccentNeonGreen,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = node.summary,
                fontSize = 13.sp,
                color = TextPrimary
            )

            if (node.properties.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    node.properties.entries.take(3).forEach { (k, v) ->
                        Text(
                            text = "$k: $v",
                            fontSize = 9.sp,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            if (node.tier == MemoryTier.LEARNED_PATTERN) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onPromote,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, AccentNeonGreen.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.TrendingUp, contentDescription = null, tint = AccentNeonGreen, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Promote to Long-Term", fontSize = 10.sp, color = AccentNeonGreen)
                    }
                }
            }
        }
    }
}
