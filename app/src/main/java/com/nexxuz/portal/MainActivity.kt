package com.nexxuz.portal

import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.nexxuz.portal.config.AppConfig
import com.nexxuz.portal.data.Site
import com.nexxuz.portal.databinding.ActivityMainBinding
import com.nexxuz.portal.ui.RegionAdapter
import com.nexxuz.portal.ui.SiteAdapter
import com.nexxuz.portal.util.AppLauncher

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: PortalViewModel

    private lateinit var regionAdapter: RegionAdapter
    private lateinit var siteAdapter: SiteAdapter

    private val isLandscape: Boolean
        get() = resources   .configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = ViewModelProvider(this)[PortalViewModel::class.java]

        setupTabs()
        setupRegionList()
        setupSitesGrid()
        setupSettings()

        binding.swipeRefresh.setColorSchemeColors(getColor(R.color.primary))
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(getColor(R.color.surface_container))
        binding.swipeRefresh.setOnRefreshListener {
            vm.reload(AppConfig.apiBase(this))
        }

        observeViewModel()
        vm.ensureLoaded(AppConfig.apiBase(this))

        // As a home launcher, Back should not exit to a blank screen — the user
        // is already "home". Swallow it so the launcher stays put.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* stay on launcher */ }
        })
    }

    // ── Tabs (VCOMM / BMS / BLM) ──────────────────────────────────
    private fun setupTabs() {
        val tabs = binding.appTabs
        if (tabs.tabCount == 0) {
            AppConfig.APPS.forEach { app ->
                tabs.addTab(tabs.newTab().setText(app.label))
            }
        }
        tabs.getTabAt(vm.selectedAppIndex)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                vm.selectedAppIndex = tab.position
                render()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    // ── Region selector ──────────────────────────────────────────
    private fun setupRegionList() {
        regionAdapter = RegionAdapter { code ->
            vm.selectedRegion = code
            regionAdapter.setSelected(code)
            render()
        }
        binding.regionRecycler.apply {
            layoutManager = if (isLandscape) {
                LinearLayoutManager(this@MainActivity, RecyclerView.VERTICAL, false)
            } else {
                LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            }
            adapter = regionAdapter
            (this.layoutManager as? LinearLayoutManager)?.let {
                // small gap between chips via item decoration
            }
        }
        binding.regionRecycler.addItemDecoration(GapDecoration(dp(6), isLandscape))
    }

    // ── Sites grid ───────────────────────────────────────────────
    private fun setupSitesGrid() {
        siteAdapter = SiteAdapter { site -> onSiteClicked(site) }
        val glm = GridLayoutManager(this, 3)
        binding.sitesRecycler.layoutManager = glm
        binding.sitesRecycler.adapter = siteAdapter
        // Compute span from the actual content width once it is measured.
        binding.sitesRecycler.post {
            val cellPx = dp(96)
            val span = (binding.sitesRecycler.width / cellPx).coerceAtLeast(2)
            glm.spanCount = span
        }
    }

    private fun onSiteClicked(site: Site) {
        val app = AppConfig.APPS[vm.selectedAppIndex]
        val ipInfo = site.ipFor(app.apiKey)
        if (ipInfo == null) {
            toast("IP tidak tersedia untuk ${app.label} di site ini")
            return
        }
        when (vm.statuses.value?.get(site.siteCode)) {
            null -> { toast("${site.siteName} masih dicek, harap tunggu…"); return }
            false -> { toast("${site.siteName} sedang offline"); return }
            else -> { /* online */ }
        }

        val pkg = AppConfig.packageFor(this, app)
        when (val r = AppLauncher.launch(this, pkg, ipInfo.ip, ipInfo.port, site.siteName, site.siteCode)) {
            is AppLauncher.Result.Success ->
                toast("Membuka ${app.label} — ${site.siteName}")
            is AppLauncher.Result.NotInstalled ->
                AlertDialog.Builder(this)
                    .setTitle("${app.label} belum terpasang")
                    .setMessage("Aplikasi $pkg tidak ditemukan di perangkat ini. " +
                        "Pastikan APK ${app.label} sudah terpasang, atau ubah nama paket di Pengaturan.")
                    .setPositiveButton("OK", null)
                    .show()
            is AppLauncher.Result.Failed ->
                toast("Gagal membuka ${app.label}: ${r.message}")
        }
    }

    // ── Settings dialog (API base + package names) ───────────────
    private fun setupSettings() {
        binding.headerBar.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun showSettingsDialog() {
        val pad = dp(20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }

        fun field(label: String, value: String): EditText {
            val tv = TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.on_surface_variant))
                textSize = 12f
                setPadding(0, dp(12), 0, dp(4))
            }
            val et = EditText(this).apply {
                setText(value)
                setTextColor(getColor(R.color.on_surface))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
            }
            container.addView(tv)
            container.addView(et)
            return et
        }

        val apiEt = field(getString(R.string.cfg_api_base), AppConfig.apiBase(this))
        val vcommEt = field(getString(R.string.cfg_pkg_vcomm), AppConfig.packageFor(this, AppConfig.APPS[0]))
        val bmsEt = field(getString(R.string.cfg_pkg_bms), AppConfig.packageFor(this, AppConfig.APPS[1]))
        val blmEt = field(getString(R.string.cfg_pkg_blm), AppConfig.packageFor(this, AppConfig.APPS[2]))

        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                AppConfig.save(
                    this,
                    apiEt.text.toString(),
                    vcommEt.text.toString(),
                    bmsEt.text.toString(),
                    blmEt.text.toString()
                )
                vm.reload(AppConfig.apiBase(this))
                toast("Pengaturan disimpan")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Observe & render ─────────────────────────────────────────
    private fun observeViewModel() {
        vm.phase.observe(this) { applyPhase(it) }
        vm.sites.observe(this) { rebuildRegions(); render() }
        vm.regions.observe(this) { rebuildRegions() }
        vm.statuses.observe(this) { render() }
        vm.errorMessage.observe(this) { msg ->
            if (msg != null) toast("Gagal memuat data: $msg")
        }
    }

    private fun applyPhase(phase: PortalViewModel.Phase) {
        when (phase) {
            PortalViewModel.Phase.LOADING -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.swipeRefresh.visibility = View.INVISIBLE
                binding.emptyView.root.visibility = View.GONE
            }
            PortalViewModel.Phase.CONTENT -> {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.visibility = View.VISIBLE
                binding.swipeRefresh.isRefreshing = false
                render()
            }
            PortalViewModel.Phase.ERROR -> {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                binding.swipeRefresh.visibility = View.VISIBLE
                render()
            }
        }
    }

    private fun rebuildRegions() {
        val sites = vm.sites.value ?: emptyList()
        val regions = vm.regions.value ?: emptyList()
        val entries = mutableListOf<RegionAdapter.Entry>()
        entries.add(RegionAdapter.Entry("ALL", getString(R.string.all_regions), sites.size))
        regions.forEach { r ->
            val count = sites.count { it.regionCode == r.regionCode }
            entries.add(RegionAdapter.Entry(r.regionCode, r.regionName, count))
        }
        val unassigned = sites.count { it.regionCode == null }
        if (unassigned > 0) {
            entries.add(RegionAdapter.Entry("NONE", getString(R.string.no_region), unassigned))
        }
        regionAdapter.submit(entries, vm.selectedRegion)
    }

    private fun render() {
        val filtered = vm.sitesForSelectedRegion()
        val app = AppConfig.APPS[vm.selectedAppIndex]

        // Title
        binding.contentTitle.text = when (vm.selectedRegion) {
            "ALL" -> getString(R.string.all_regions)
            "NONE" -> getString(R.string.no_region)
            else -> vm.regions.value?.firstOrNull { it.regionCode == vm.selectedRegion }?.regionName
                ?: vm.selectedRegion
        }
        binding.contentCount.text = "${filtered.size} site"

        siteAdapter.submit(filtered, app.apiKey, vm.statuses.value ?: emptyMap())

        // Empty state only after a successful load
        val showEmpty = vm.phase.value == PortalViewModel.Phase.CONTENT && filtered.isEmpty()
        binding.emptyView.root.visibility = if (showEmpty) View.VISIBLE else View.GONE
        binding.sitesRecycler.visibility = if (showEmpty) View.INVISIBLE else View.VISIBLE

        updateFooter()
    }

    private fun updateFooter() {
        val sites = vm.sites.value ?: emptyList()
        val statuses = vm.statuses.value ?: emptyMap()
        val total = sites.size
        val online = sites.count { statuses[it.siteCode] == true }
        val label = if (total > 0 && online < total) "Alert" else "Optimal"
        val check = vm.lastCheck.value
        val checkTxt = if (check != null) "  —  Cek: $check" else ""
        binding.statusBar.text = "System: $label  —  $online/$total Online$checkTxt"
    }

    // ── Helpers ──────────────────────────────────────────────────
    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    /** Adds spacing between region chips (horizontal in portrait, vertical in landscape). */
    private class GapDecoration(
        private val gap: Int,
        private val vertical: Boolean
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            if (vertical) outRect.bottom = gap else outRect.right = gap
        }
    }
}
