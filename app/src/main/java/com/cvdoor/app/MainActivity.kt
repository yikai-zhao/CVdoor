package com.cvdoor.app

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cvdoor.app.flow.*
import com.cvdoor.app.ui.modern.ModernDashboard
import com.cvdoor.app.ui.screens.AtsTrendScreen
import com.cvdoor.app.ui.screens.BuyCreditsScreen
import com.cvdoor.app.ui.screens.ClassicOptimizeScreen
import com.cvdoor.app.ui.screens.HistoryScreen
import com.cvdoor.app.ui.screens.InfoScreen
import com.cvdoor.app.ui.screens.JobTrackerScreen
import com.cvdoor.app.ui.screens.JobsHubScreen
import com.cvdoor.app.ui.screens.ProfileScreen
import com.cvdoor.app.ui.screens.SampleDetailsScreen
import com.cvdoor.app.ui.theme.CVDoorTheme
import com.cvdoor.app.ui.theme.Night
import com.cvdoor.app.ui.theme.NeonBlueEnd
import com.cvdoor.app.ui.theme.NightElevated
import com.cvdoor.app.ui.theme.TextPrimary
import com.cvdoor.app.ui.theme.TextSecondary
import com.cvdoor.app.util.JobsViewModel
import com.cvdoor.app.util.MainAppVM
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BOTTOM_NAV_ROUTES = setOf("home_v3", "jobs_hub", "history_v3", "profile")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CVDoorTheme {
                Surface(modifier = Modifier.statusBarsPadding()) {
                    CVATSApp()
                }
            }
        }
    }
}

@Composable
fun CVATSApp() {
    val nav = rememberNavController()
    val appVm: MainAppVM = viewModel()
    val flowVm: FlowViewModel = viewModel()
    val jobsVm: JobsViewModel = viewModel()
    val flowState by flowVm.state.collectAsState()
    val uid by appVm.uid.collectAsState()

    // Keep JobsViewModel in sync with logged-in user
    LaunchedEffect(uid) { jobsVm.setUserId(uid) }

    val navBackStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomNav = currentRoute in BOTTOM_NAV_ROUTES

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                BottomNav(nav = nav, currentRoute = currentRoute)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = "home_v3",
            modifier = Modifier.padding(innerPadding)
        ) {

            // ── V3 Main Screens ──────────────────────────────────────────────

            composable("home_v3") {
                val credits by appVm.credits.collectAsState()
                val history by appVm.history.collectAsState()
                val recent = history.firstOrNull()
                val match  = recent?.afterTotal ?: 0
                val delta  = recent?.let { it.afterTotal - it.beforeTotal } ?: 0
                val cov    = recent?.dimsAfter?.getOrElse(1) { 0 } ?: 0
                val label  = recent?.jdText?.lineSequence()?.firstOrNull()?.trim()
                    ?.take(40).orEmpty().ifBlank { "No recent record" }

                ModernDashboard(
                    credits = credits,
                    fileName = label,
                    matchRate = match,
                    coverage = cov,
                    delta = delta,
                    onHistory = { nav.navigate("history_v3") },
                    onLogout = {
                        appVm.logout()
                        nav.navigate("landing") { popUpTo("home_v3") { inclusive = true } }
                    },
                    onBuyCredits = { nav.navigate("buy") },
                    onOptimize   = { nav.navigate("landing") },
                    onInfo       = { nav.navigate("info") },
                    radarBefore  = recent?.dimsBefore?.toList() ?: emptyList(),
                    radarAfter   = recent?.dimsAfter?.toList() ?: emptyList(),
                    onExportRecent = { nav.navigate("sample") },
                    onOpenRecentDetails = {
                        val rec = recent ?: return@ModernDashboard
                        nav.navigate("details/${rec.id}")
                    },
                    recentAvailable = recent != null
                )
            }

            composable("jobs_hub") {
                val savedJobs by jobsVm.savedJobs.collectAsState()
                JobsHubScreen(
                    savedJobs = savedJobs,
                    onStartScan = { jdText ->
                        flowVm.setJdText(jdText)
                        nav.navigate("resume_select")
                    },
                    onRedoJob = { job ->
                        flowVm.setJdText(job.jdText)
                        nav.navigate("resume_select")
                    },
                    onDeleteJob = { job -> jobsVm.deleteJob(job) },
                    onBack = { nav.popBackStack() }
                )
            }

            composable("history_v3") {
                val records by appVm.history.collectAsState()
                HistoryScreen(
                    records = records,
                    onBack = { nav.popBackStack() },
                    onLogout = {
                        appVm.logout()
                        nav.navigate("home_v3") { popUpTo("home_v3") { inclusive = true } }
                    },
                    onOpenDetails = { rec -> nav.navigate("details/${rec.id}") },
                    onReOptimize = { rec ->
                        appVm.setOptimizePrefill(rec.resumeText, rec.jdText)
                        nav.navigate("optimize")
                    },
                    onDelete = { rec -> appVm.deleteRecord(rec.id) },
                    onClearAll = { appVm.clearHistory() }
                )
            }

            composable("profile") {
                val displayName by appVm.displayName.collectAsState()
                val credits by appVm.credits.collectAsState()
                val history by appVm.history.collectAsState()
                ProfileScreen(
                    displayName = displayName,
                    credits = credits,
                    history = history,
                    onLogout = {
                        appVm.logout()
                        nav.navigate("home_v3") { popUpTo("home_v3") { inclusive = true } }
                    },
                    onBuyCredits = { nav.navigate("buy") },
                    onBack = { nav.popBackStack() }
                )
            }

            composable("ats_trend") {
                val history by appVm.history.collectAsState()
                AtsTrendScreen(
                    records = history,
                    onBack = { nav.popBackStack() }
                )
            }

            composable("job_tracker") {
                val uidVal = uid
                val apps by jobsVm.applications.collectAsState()
                JobTrackerScreen(
                    applications = apps,
                    onAddApplication = { title, company, score ->
                        if (!uidVal.isNullOrBlank()) {
                            jobsVm.addApplication(uidVal, title, company, score)
                        }
                    },
                    onUpdateStage = { app, stage -> jobsVm.updateStage(app, stage) },
                    onDelete = { app -> jobsVm.deleteApplication(app) },
                    onBack = { nav.popBackStack() }
                )
            }

            // ── V2 Flow ──────────────────────────────────────────────────────

            composable("landing") {
                LandingScreen(onStart = { nav.navigate("resume_select") })
            }

            composable("resume_select") {
                val savedResumes by flowVm.savedResumes.collectAsState()
                ResumeSelectScreen(
                    savedResumes = savedResumes,
                    onSelectResume = {
                        flowVm.selectSavedResume(it)
                        nav.navigate("industry_select")
                    },
                    onDeleteResume = { flowVm.deleteSavedResume(it) },
                    onUploadNew = { nav.navigate("resume_upload") },
                    onBack = { nav.popBackStack() }
                )
            }

            composable("resume_upload") {
                ResumeUploadScreen(
                    state = flowState,
                    vm = flowVm,
                    onBack = { nav.popBackStack() },
                    onNext = {
                        nav.navigate("industry_select") {
                            popUpTo("resume_upload") { inclusive = true }
                        }
                    }
                )
            }

            composable("industry_select") {
                IndustrySelectScreen(
                    state = flowState,
                    vm = flowVm,
                    onBack = { nav.popBackStack() },
                    onNext = { nav.navigate("jd_input") }
                )
            }

            composable("jd_input") {
                JdInputScreen(
                    state = flowState,
                    vm = flowVm,
                    onBack = { nav.popBackStack() },
                    onOptimize = { nav.navigate("demo_preview") },
                    onChangeResume = { nav.navigate("resume_select") },
                    onChangeIndustry = { nav.navigate("industry_select") }
                )
            }

            composable("demo_preview") {
                DemoPreviewScreen(
                    state = flowState,
                    onBack = { nav.popBackStack() },
                    onPayNow = { nav.navigate("payment") }
                )
            }

            composable("payment") {
                PaymentScreen(
                    state = flowState,
                    vm = flowVm,
                    onBack = { nav.popBackStack() },
                    onPaymentStarted = { nav.navigate("payment_processing") }
                )
            }

            composable("payment_processing") {
                PaymentProcessingScreen(
                    state = flowState,
                    vm = flowVm,
                    onSuccess = {
                        nav.navigate("result") {
                            popUpTo("payment_processing") { inclusive = true }
                        }
                    },
                    onBackToPayment = {
                        flowVm.resetPayment()
                        nav.navigate("payment") {
                            popUpTo("payment_processing") { inclusive = true }
                        }
                    },
                    onBackToHome = {
                        flowVm.resetPayment()
                        flowVm.reset()
                        nav.navigate("home_v3") {
                            popUpTo("home_v3") { inclusive = true }
                        }
                    }
                )
            }

            composable("result") {
                ResultScreen(
                    state = flowState,
                    onBack = { nav.popBackStack() },
                    onReset = {
                        flowVm.reset()
                        nav.navigate("home_v3") {
                            popUpTo("home_v3") { inclusive = true }
                        }
                    },
                    onCoverLetter = { nav.navigate("cover_letter") },
                    onEdit = { nav.navigate("resume_edit") },
                    onFillData = { nav.navigate("data_supplement") },
                    onPreSubmit = { nav.navigate("pre_submit") }
                )
            }

            composable("resume_edit") {
                ResumeEditScreen(
                    state = flowState,
                    onBack = { nav.popBackStack() },
                    onSave = {
                        flowVm.updateOptimizedResume(it)
                        nav.popBackStack()
                    },
                    onSaveAndReoptimize = { nav.navigate("demo_preview") },
                    onFillData = { nav.navigate("data_supplement") }
                )
            }

            composable("data_supplement") {
                DataSupplementScreen(
                    industryId = flowState.industryId,
                    vm = flowVm,
                    onBack = { nav.popBackStack() },
                    onSaveAndApply = { nav.navigate("result") { popUpTo("result") { inclusive = true } } },
                    onSkip = { nav.popBackStack() }
                )
            }

            composable("cover_letter") {
                CoverLetterScreen(
                    state = flowState,
                    vm = flowVm,
                    onBack = { nav.popBackStack() },
                    onFillData = { nav.navigate("data_supplement") }
                )
            }

            composable("pre_submit") {
                PreSubmitCheckScreen(
                    state = flowState,
                    onBack = { nav.popBackStack() },
                    onFillData = { nav.navigate("data_supplement") }
                )
            }

            // ── Legacy V1 routes kept for compatibility ──────────────────────

            composable("resume_input") {
                ResumeInputScreen(
                    state = flowState,
                    vm = flowVm,
                    onBack = { nav.popBackStack() },
                    onNext = { nav.navigate("jd_input") }
                )
            }

            composable("processing") {
                val phase = flowState.phase
                LaunchedEffect(phase) {
                    if (phase == FlowPhase.SUCCESS) {
                        nav.navigate("result") {
                            popUpTo("processing") { inclusive = true }
                        }
                    }
                }
                ProcessingScreen(
                    state = flowState,
                    onRetry = {
                        flowVm.retry()
                        nav.popBackStack()
                    }
                )
            }

            composable("signin") {
                com.cvdoor.app.ui.screens.SignInScreen(
                    onSignedIn = {
                        nav.navigate("home_v3") { popUpTo("signin") { inclusive = true } }
                    }
                )
            }

            composable("home") {
                val credits by appVm.credits.collectAsState()
                val history by appVm.history.collectAsState()
                val recent = history.firstOrNull()
                val match  = recent?.afterTotal ?: 0
                val delta  = recent?.let { it.afterTotal - it.beforeTotal } ?: 0
                val cov    = recent?.dimsAfter?.getOrElse(1) { 0 } ?: 0
                val label  = recent?.jdText?.lineSequence()?.firstOrNull()?.trim()
                    ?.take(40).orEmpty().ifBlank { "No recent record" }

                ModernDashboard(
                    credits = credits,
                    fileName = label,
                    matchRate = match,
                    coverage = cov,
                    delta = delta,
                    onHistory = { nav.navigate("history") },
                    onLogout = {
                        appVm.logout()
                        nav.navigate("landing") { popUpTo("home") { inclusive = true } }
                    },
                    onBuyCredits = { nav.navigate("buy") },
                    onOptimize   = { nav.navigate("resume_input") },
                    onInfo       = { nav.navigate("info") },
                    radarBefore  = recent?.dimsBefore?.toList() ?: emptyList(),
                    radarAfter   = recent?.dimsAfter?.toList() ?: emptyList(),
                    onExportRecent = { nav.navigate("sample") },
                    onOpenRecentDetails = {
                        val rec = recent ?: return@ModernDashboard
                        nav.navigate("details/${rec.id}")
                    },
                    recentAvailable = recent != null
                )
            }

            composable("optimize") {
                val prefill = appVm.takeOptimizePrefill()
                ClassicOptimizeScreen(
                    vm = appVm,
                    prefillResume = prefill?.first,
                    prefillJD = prefill?.second,
                    onBack = { nav.popBackStack() },
                    onSaved = { newId ->
                        nav.navigate("details/$newId") {
                            popUpTo("home_v3") { inclusive = false }
                        }
                    }
                )
            }

            composable("history") {
                val records by appVm.history.collectAsState()
                HistoryScreen(
                    records = records,
                    onBack = { nav.popBackStack() },
                    onLogout = {
                        appVm.logout()
                        nav.navigate("landing") { popUpTo("home") { inclusive = true } }
                    },
                    onOpenDetails = { rec -> nav.navigate("details/${rec.id}") },
                    onReOptimize = { rec ->
                        appVm.setOptimizePrefill(rec.resumeText, rec.jdText)
                        nav.navigate("optimize")
                    },
                    onDelete = { rec -> appVm.deleteRecord(rec.id) },
                    onClearAll = { appVm.clearHistory() }
                )
            }

            composable(
                route = "details/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getLong("id") ?: return@composable
                val records by appVm.history.collectAsState()
                val rec = records.find { it.id == id }
                if (rec != null) {
                    com.cvdoor.app.ui.screens.RecordDetailsScreen(
                        resume = rec.resumeText,
                        jd = rec.jdText,
                        optimized = rec.optimizedText,
                        before = rec.beforeTotal,
                        after = rec.afterTotal,
                        dimsBefore = rec.dimsBefore,
                        dimsAfter = rec.dimsAfter,
                        analysis = rec.analysis,
                        onBack = { nav.popBackStack() }
                    )
                } else {
                    Text("Record not found (id=$id)")
                }
            }

            composable("buy") { BuyCreditsScreen(onBack = { nav.popBackStack() }, vm = appVm) }
            composable("info") { InfoScreen(onBack = { nav.popBackStack() }) }
            composable("sample") { SampleDetailsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

@Composable
private fun BottomNav(nav: NavController, currentRoute: String?) {
    NavigationBar(containerColor = NightElevated) {
        NavigationBarItem(
            selected = currentRoute == "home_v3",
            onClick = {
                if (currentRoute != "home_v3") {
                    nav.navigate("home_v3") { launchSingleTop = true }
                }
            },
            icon = { Icon(Icons.Outlined.Home, contentDescription = "Home") },
            label = { Text("Home") },
            colors = navBarItemColors()
        )
        NavigationBarItem(
            selected = currentRoute == "jobs_hub",
            onClick = {
                if (currentRoute != "jobs_hub") {
                    nav.navigate("jobs_hub") { launchSingleTop = true }
                }
            },
            icon = { Icon(Icons.Outlined.Work, contentDescription = "Jobs") },
            label = { Text("Jobs") },
            colors = navBarItemColors()
        )
        NavigationBarItem(
            selected = false,
            onClick = { nav.navigate("landing") },
            icon = { Icon(Icons.Outlined.Star, contentDescription = "Optimize") },
            label = { Text("Optimize") },
            colors = navBarItemColors()
        )
        NavigationBarItem(
            selected = currentRoute == "history_v3",
            onClick = {
                if (currentRoute != "history_v3") {
                    nav.navigate("history_v3") { launchSingleTop = true }
                }
            },
            icon = { Icon(Icons.Outlined.History, contentDescription = "History") },
            label = { Text("History") },
            colors = navBarItemColors()
        )
        NavigationBarItem(
            selected = currentRoute == "profile",
            onClick = {
                if (currentRoute != "profile") {
                    nav.navigate("profile") { launchSingleTop = true }
                }
            },
            icon = { Icon(Icons.Outlined.Person, contentDescription = "Me") },
            label = { Text("Me") },
            colors = navBarItemColors()
        )
    }
}

@Composable
private fun navBarItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = NeonBlueEnd,
    selectedTextColor = NeonBlueEnd,
    indicatorColor = NeonBlueEnd.copy(alpha = 0.15f),
    unselectedIconColor = TextSecondary,
    unselectedTextColor = TextSecondary
)

/* ------- PDF 导出函数保留 ------- */

private data class ExportPayload(
    val company: String,
    val before: Int,
    val after: Int,
    val dimsBefore: List<Int>,
    val dimsAfter: List<Int>,
    val resume: String,
    val jd: String,
    val optimized: String
) {
    companion object {
        fun fromRecord(r: com.cvdoor.app.data.OptimizationRecord) = ExportPayload(
            company = r.jdText.lineSequence().firstOrNull()?.trim().orEmpty(),
            before = r.beforeTotal,
            after = r.afterTotal,
            dimsBefore = r.dimsBefore,
            dimsAfter = r.dimsAfter,
            resume = r.resumeText,
            jd = r.jdText,
            optimized = r.optimizedText
        )
    }
}

private fun exportDetailsToPdf(context: Context, p: ExportPayload) {
    val doc = PdfDocument()
    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
    val page = doc.startPage(pageInfo)
    val canvas = page.canvas

    val titlePaint = Paint().apply {
        isAntiAlias = true
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFFFFFFFF.toInt()
    }
    val bodyPaint = Paint().apply {
        isAntiAlias = true
        textSize = 12f
        color = 0xFFFFFFFF.toInt()
    }
    val dimPaint = Paint().apply {
        isAntiAlias = true
        textSize = 12f
        color = 0xFF7CC4FF.toInt()
    }

    var y = 40f
    fun line(t: String, paint: Paint = bodyPaint) {
        canvas.drawText(t, 40f, y, paint); y += 20f
    }

    canvas.drawColor(0xFF0F172A.toInt())
    line("CVDoor ATS Optimization Report", titlePaint)
    line("Company / JD: ${p.company.ifBlank { "N/A" }}")
    line("Match Rate: Before ${p.before}%  →  After ${p.after}%")
    y += 8f

    val names = listOf("Fmt", "KW", "Sem", "Title", "Read", "Rec")
    line("Dimensions:", titlePaint)
    names.indices.forEach { i ->
        val b = p.dimsBefore.getOrElse(i) { 0 }
        val a = p.dimsAfter.getOrElse(i) { 0 }
        line(" • ${names[i]}   $b%  →  $a%", dimPaint)
    }
    y += 8f

    fun block(h: String, t: String) {
        line(h, titlePaint)
        t.wrap(90).forEach { line(it) }
        y += 8f
    }

    block("Original Resume", p.resume.ifBlank { "(empty)" })
    block("Optimized Resume", p.optimized.ifBlank { "(backend not returned)" })
    block("Job Description", p.jd.ifBlank { "(empty)" })

    doc.finishPage(page)

    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val outFile = File(dir, "cvdoor_report_$ts.pdf")
    FileOutputStream(outFile).use { doc.writeTo(it) }
    doc.close()

    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, "Share optimization report"))
}

private fun String.wrap(maxCols: Int): List<String> {
    if (isBlank()) return listOf("")
    val out = mutableListOf<String>()
    var i = 0
    while (i < length) {
        val end = (i + maxCols).coerceAtMost(length)
        out += substring(i, end)
        i = end
    }
    return out
}
