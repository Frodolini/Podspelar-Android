package no.frodolini.podspelar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFF81C784)) else lightColorScheme(primary = FrodeGreen)
            MaterialTheme(colorScheme = scheme) { App() }
        }
    }

    override fun onStart() {
        super.onStart()
        Playback.connect(applicationContext)
    }

    override fun onStop() {
        Playback.disconnect()
        super.onStop()
    }
}

private val speeds = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.8f)

/** Hentar nye episodar for alle favorittane. Returnerer kor mange som feila. */
private suspend fun refreshFavorites(): Int {
    val feeds = Store.favorites.map { it.feedUrl }
    val results = withContext(Dispatchers.IO) {
        feeds.map { url -> async { runCatching { fetchFeed(url) }.getOrNull() } }.awaitAll()
    }
    results.filterNotNull().forEach { Store.setFeed(it) }
    Store.rememberRefresh()
    return results.count { it == null }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openPod by remember { mutableStateOf<Podcast?>(null) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // Søket ligg her, slik at det står att når ein går inn i ein pod og tilbake.
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Podcast>?>(null) }

    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
    }

    fun refresh() {
        if (refreshing || Store.favorites.isEmpty()) return
        refreshing = true
        scope.launch {
            val failed = refreshFavorites()
            refreshing = false
            if (failed > 0) message = "Fekk ikkje henta $failed av poddane. Sjekk nettet og prøv att."
        }
    }

    // Hent nye episodar når appen blir opna, men ikkje oftare enn kvar halvtime.
    LaunchedEffect(Unit) {
        if (System.currentTimeMillis() - Store.lastRefreshMs > 30 * 60_000) refresh()
    }

    // Hald avspelingsstatusen fersk medan appen er framme.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                Playback.refresh()
                delay(500)
            }
        }
    }

    // Android 13+ krev løyve for å visa varsel (nedlasting og avspeling). Spør éin gong.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Store.askedNotifications &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Store.rememberAsked()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = showPlayer || openPod != null || tab != 0) {
        when {
            showPlayer -> showPlayer = false
            openPod != null -> openPod = null
            else -> tab = 0
        }
    }

    val error = message ?: Playback.error ?: DownloadService.error
    if (error != null) {
        val close = { message = null; Playback.error = null; DownloadService.error = null }
        AlertDialog(
            onDismissRequest = close,
            confirmButton = { TextButton(onClick = close) { Text("OK") } },
            text = { Text(error) },
        )
    }

    val pod = openPod
    Scaffold(
        topBar = {
            when {
                showPlayer -> GreenBar("Spelar no", onBack = { showPlayer = false })
                pod != null -> GreenBar(pod.title.ifBlank { "Podkast" }, onBack = { openPod = null })
                else -> GreenBar("Frodes Podspelar", version = version) {
                    if (tab == 0 && Store.favorites.isNotEmpty()) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                color = Color.White, strokeWidth = 2.dp,
                                modifier = Modifier.padding(end = 14.dp).size(22.dp),
                            )
                        } else {
                            IconButton(onClick = { refresh() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Hent nye episodar")
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column {
                if (!showPlayer) MiniPlayer(onOpen = { showPlayer = true })
                if (!showPlayer && pod == null) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Default.Star, null) }, label = { Text("Favorittar") },
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.Default.Search, null) }, label = { Text("Finn") },
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(IconDownload, null) }, label = { Text("Nedlasta") },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                showPlayer -> PlayerScreen()
                pod != null -> PodcastScreen(pod, onResolved = { openPod = it })
                tab == 0 -> FavoritesScreen(openPod = { openPod = it }, goFind = { tab = 1 })
                tab == 1 -> FindScreen(
                    query = query, onQuery = { query = it },
                    results = results, onResults = { results = it },
                    openPod = { openPod = it },
                )
                else -> DownloadsScreen()
            }
        }
    }
}

// ---------------------------------------------------------------- Favorittar

@Composable
fun FavoritesScreen(openPod: (Podcast) -> Unit, goFind: () -> Unit) {
    if (Store.favorites.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Ingen favorittar enno", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Finn ein pod og trykk på stjerna, så dukkar dei nyaste episodane opp her.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = goFind) { Text("Finn poddar") }
        }
        return
    }
    // Dei nyaste episodane på tvers av alle favorittane
    val newest by remember {
        derivedStateOf {
            Store.favorites
                .flatMap { Store.episodes[it.feedUrl].orEmpty().take(15) }
                .sortedByDescending { it.pubMs }
                .take(60)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(Store.favorites, key = { it.feedUrl }) { p ->
                    Column(Modifier.width(84.dp).clickable { openPod(p) }) {
                        Cover(p.artUrl, 84.dp)
                        Text(
                            p.title, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        item { SectionTitle("Nyaste episodar") }
        if (newest.isEmpty()) {
            item {
                Text(
                    "Hentar episodar …", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(newest, key = { it.id }) { e ->
            EpisodeRow(e, showPodcast = true)
            HorizontalDivider()
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    )
}

// ---------------------------------------------------------------- Finn

@Composable
fun FindScreen(
    query: String, onQuery: (String) -> Unit,
    results: List<Podcast>?, onResults: (List<Podcast>?) -> Unit,
    openPod: (Podcast) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val isUrl = query.trim().startsWith("http://") || query.trim().startsWith("https://")

    fun go() {
        val q = query.trim()
        if (q.isEmpty()) return
        keyboard?.hide()
        if (isUrl) {
            // Ei RSS-adresse limt rett inn
            openPod(Podcast(q, "", "", ""))
            return
        }
        searching = true
        failed = false
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { searchPodcasts(q) }.getOrNull() }
            searching = false
            failed = r == null
            onResults(r)
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            placeholder = { Text("Søk etter pod, eller lim inn RSS-adresse") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery(""); onResults(null) }) {
                    Icon(Icons.Default.Close, contentDescription = "Tøm")
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        when {
            searching -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            isUrl -> Button(onClick = { go() }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Opne RSS-adressa")
            }
            failed -> Hint("Søket feila. Sjekk nettet og prøv att.")
            results == null -> Hint("Søket går i Apple sin opne podkastkatalog. Finn du ikkje podden der, kan du lima inn RSS-adressa hans i søkjefeltet.")
            results.isEmpty() -> Hint("Ingen treff.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.feedUrl }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { openPod(p) }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(p.artUrl, 56.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(p.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                p.author, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StarButton(p)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun StarButton(p: Podcast) {
    val fav = Store.isFavorite(p.feedUrl)
    IconButton(onClick = { Store.toggleFavorite(p) }) {
        if (fav) Icon(Icons.Default.Star, contentDescription = "Fjern frå favorittar", tint = StarYellow)
        else Icon(IconStarBorder, contentDescription = "Legg til i favorittar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Ein pod

@Composable
fun PodcastScreen(pod: Podcast, onResolved: (Podcast) -> Unit) {
    var loading by remember(pod.feedUrl) { mutableStateOf(false) }
    var failed by remember(pod.feedUrl) { mutableStateOf(false) }
    val episodes = Store.episodes[pod.feedUrl]

    LaunchedEffect(pod.feedUrl) {
        loading = true
        val feed = withContext(Dispatchers.IO) { runCatching { fetchFeed(pod.feedUrl) }.getOrNull() }
        loading = false
        if (feed == null) {
            failed = true
        } else {
            Store.setFeed(feed)
            // Opna frå ei RSS-adresse: no veit me tittel og omslag
            if (pod.title.isBlank() || pod.artUrl.isBlank()) {
                onResolved(
                    pod.copy(
                        title = pod.title.ifBlank { feed.podcast.title },
                        author = pod.author.ifBlank { feed.podcast.author },
                        artUrl = pod.artUrl.ifBlank { feed.podcast.artUrl },
                    )
                )
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(pod.artUrl, 96.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(pod.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        pod.author, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pod.title.isNotBlank()) {
                        val fav = Store.isFavorite(pod.feedUrl)
                        TextButton(onClick = { Store.toggleFavorite(pod) }, contentPadding = PaddingValues(0.dp)) {
                            if (fav) Icon(Icons.Default.Star, null, tint = StarYellow) else Icon(IconStarBorder, null)
                            Text(if (fav) " Favoritt" else " Legg til i favorittar")
                        }
                    }
                }
            }
        }
        if (loading && episodes == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        if (failed && episodes == null) {
            item { Hint("Klarte ikkje henta episodane. Sjekk nettet, eller at adressa er ein RSS-feed.") }
        }
        if (episodes != null) {
            item { SectionTitle("${episodes.size} episodar") }
            items(episodes, key = { it.id }) { e ->
                EpisodeRow(e, showPodcast = false)
                HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------- Episoderad

@Composable
fun EpisodeRow(e: Episode, showPodcast: Boolean) {
    val ctx = LocalContext.current
    var expanded by remember(e.id) { mutableStateOf(false) }
    var confirmDelete by remember(e.id) { mutableStateOf(false) }
    val pos = Store.positions[e.id]
    val playingThis = Playback.currentId == e.id && Playback.isPlaying

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text("Slette nedlastinga av «${e.title}»?") },
            confirmButton = {
                TextButton(onClick = { Store.deleteDownload(e); confirmDelete = false }) { Text("Slett") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Avbryt") } },
        )
    }

    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showPodcast) {
                Cover(e.artUrl, 48.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    e.title, fontWeight = FontWeight.Medium, maxLines = if (expanded) 6 else 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 20.sp,
                )
                val meta = listOfNotNull(
                    e.podcastTitle.takeIf { showPodcast && it.isNotBlank() },
                    fmtDate(e.pubMs).takeIf { it.isNotEmpty() },
                    fmtLength(e.durationSec.toLong()).takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                Text(
                    meta, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Framdrift: ferdig høyrt, eller kor mykje som står att
                if (pos != null && pos.done) {
                    Text("✓ Høyrt", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                } else if (pos != null && pos.posMs > 5_000) {
                    val left = if (pos.durMs > 0) fmtLength((pos.durMs - pos.posMs) / 1000) + " att" else "Påbyrja"
                    Text(left, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            // Nedlasting: last ned / avbryt / slett
            val p = Store.progress[e.id]
            when {
                p != null -> IconButton(onClick = { DownloadService.cancel(ctx, e) }) {
                    Box(contentAlignment = Alignment.Center) {
                        if (p > 0f) CircularProgressIndicator(progress = { p }, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        else CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        Icon(Icons.Default.Close, contentDescription = "Avbryt nedlasting", modifier = Modifier.size(14.dp))
                    }
                }
                Store.isDownloaded(e) -> IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Check, contentDescription = "Nedlasta. Trykk for å sletta.", tint = MaterialTheme.colorScheme.primary)
                }
                else -> IconButton(onClick = { DownloadService.start(ctx, e) }) {
                    Icon(IconDownload, contentDescription = "Last ned", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { if (playingThis) Playback.toggle() else Playback.play(e) }) {
                Icon(
                    if (playingThis) IconPause else Icons.Default.PlayArrow,
                    contentDescription = if (playingThis) "Pause" else "Spel av",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp),
                )
            }
        }
        if (expanded && e.description.isNotBlank()) {
            Text(
                e.description, fontSize = 14.sp, lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, end = 12.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Nedlasta

@Composable
fun DownloadsScreen() {
    val list = Store.downloads.values.sortedByDescending { it.pubMs }
    val active = Store.progress.size
    if (list.isEmpty() && active == 0) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Ingenting er lasta ned", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Trykk på nedlastingspila ved ein episode for å ha han klar utan nett.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val bytes = remember(list.size) { list.sumOf { Store.fileFor(it).length() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)) {
        item {
            val parts = listOfNotNull(
                "${list.size} episodar · ${fmtSize(bytes)}",
                "$active blir lasta ned".takeIf { active > 0 },
            )
            SectionTitle(parts.joinToString(" · "))
        }
        items(list, key = { it.id }) { e ->
            EpisodeRow(e, showPodcast = true)
            HorizontalDivider()
        }
    }
}

// ---------------------------------------------------------------- Spelar

/** Den vesle spelaren nedst på skjermen. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val e = Store.last ?: return
    val loaded = Playback.currentId == e.id
    val saved = Store.positions[e.id]
    val pos = if (loaded) Playback.posMs else saved?.takeIf { !it.done }?.posMs ?: 0
    val dur = (if (loaded) Playback.durMs else saved?.durMs ?: 0).takeIf { it > 0 } ?: (e.durationSec * 1000L)
    Surface(tonalElevation = 6.dp, modifier = Modifier.clickable { onOpen() }) {
        Column {
            LinearProgressIndicator(
                progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                drawStopIndicator = {},
            )
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(e.artUrl, 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(e.title, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        e.podcastTitle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PlayPauseIcon(size = 34)
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
fun PlayPauseIcon(size: Int) {
    IconButton(onClick = { Playback.toggle() }) {
        if (Playback.loading) {
            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size((size - 8).dp))
        } else {
            Icon(
                if (Playback.isPlaying) IconPause else Icons.Default.PlayArrow,
                contentDescription = if (Playback.isPlaying) "Pause" else "Spel av",
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size.dp),
            )
        }
    }
}

/** Den store spelaren. */
@Composable
fun PlayerScreen() {
    val e = Store.last
    if (e == null) {
        Hint("Ingen episode er vald.")
        return
    }
    val loaded = Playback.currentId == e.id
    val saved = Store.positions[e.id]
    val pos = if (loaded) Playback.posMs else saved?.takeIf { !it.done }?.posMs ?: 0
    val dur = (if (loaded) Playback.durMs else saved?.durMs ?: 0).takeIf { it > 0 } ?: (e.durationSec * 1000L)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(e.artUrl, 220.dp)
        Spacer(Modifier.height(16.dp))
        Text(e.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(e.podcastTitle, fmtDate(e.pubMs)).filter { it.isNotBlank() }.joinToString(" · "),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        val shown = if (dragging) dragValue.toLong() else pos
        Slider(
            value = shown.toFloat().coerceIn(0f, dur.toFloat().coerceAtLeast(1f)),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                val target = dragValue.toLong()
                if (loaded) Playback.seekTo(target) else Store.setPos(e.id, target, dur, false)
                dragging = false
            },
            valueRange = 0f..dur.toFloat().coerceAtLeast(1f),
            enabled = dur > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(shown), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                if (dur > 0) "−" + fmtTime(dur - shown) else "", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            TextButton(onClick = { Playback.back() }, enabled = loaded) { Text("−15 s", fontSize = 17.sp) }
            FilledIconButton(onClick = { Playback.toggle() }, modifier = Modifier.size(72.dp)) {
                if (Playback.loading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                } else {
                    Icon(
                        if (Playback.isPlaying) IconPause else Icons.Default.PlayArrow,
                        contentDescription = if (Playback.isPlaying) "Pause" else "Spel av",
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            TextButton(onClick = { Playback.forward() }, enabled = loaded) { Text("+30 s", fontSize = 17.sp) }
        }

        Spacer(Modifier.height(8.dp))
        AssistChip(
            onClick = {
                val i = speeds.indexOfFirst { it == Store.speed }
                Playback.setSpeed(speeds[(i + 1) % speeds.size])
            },
            label = { Text("Fart " + fmtSpeed(Store.speed)) },
        )

        if (e.description.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Text(
                e.description, fontSize = 14.sp, lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}
