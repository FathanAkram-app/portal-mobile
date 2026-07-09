package com.nexxuz.portal

import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.nexxuz.portal.data.ImageUpload
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.nexxuz.portal.config.AppConfig
import com.nexxuz.portal.data.Region
import com.nexxuz.portal.data.Site
import com.nexxuz.portal.data.SiteIpInput
import com.nexxuz.portal.databinding.ActivityMainBinding
import com.nexxuz.portal.ui.RegionAdapter
import com.nexxuz.portal.ui.SiteAdapter
import com.nexxuz.portal.util.AppLauncher
import com.nexxuz.portal.util.ImageLoader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: PortalViewModel

    private lateinit var regionAdapter: RegionAdapter
    private lateinit var siteAdapter: SiteAdapter

    // Set by whichever site dialog is open; the picker callback routes the result here.
    private var onImagePicked: ((Uri) -> Unit)? = null
    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onImagePicked?.invoke(uri)
            onImagePicked = null
        }

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
        setupCreateSite()
        setupSearch()
        loadLogo()

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
        regionAdapter = RegionAdapter(
            onClick = { code ->
                vm.selectedRegion = code
                regionAdapter.setSelected(code)
                render()
            },
            onLongClick = { code -> showRegionActions(code) }
        )
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
        siteAdapter = SiteAdapter(
            onClick = { site -> onSiteClicked(site) },
            onLongClick = { site -> showSiteActions(site) }
        )
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
                AlertDialog.Builder(this, R.style.Theme_Portal_Dialog)
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

    // ── Site search ──────────────────────────────────────────────
    private fun setupSearch() {
        // Restore the query across rotation before wiring the watcher so the
        // restore itself doesn't bounce through render().
        if (vm.searchQuery.isNotEmpty()) {
            binding.searchSites.setText(vm.searchQuery)
            binding.searchSites.setSelection(vm.searchQuery.length)
        }
        binding.searchSites.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                vm.searchQuery = s?.toString() ?: ""
                render()
            }
        })
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
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
            }
            applyInputStyle(et)
            container.addView(tv)
            container.addView(et, inputLayoutParams())
            return et
        }

        val apiEt = field(getString(R.string.cfg_api_base), AppConfig.apiBase(this))
        val vcommEt = field(getString(R.string.cfg_pkg_vcomm), AppConfig.packageFor(this, AppConfig.APPS[0]))
        val bmsEt = field(getString(R.string.cfg_pkg_bms), AppConfig.packageFor(this, AppConfig.APPS[1]))
        val blmEt = field(getString(R.string.cfg_pkg_blm), AppConfig.packageFor(this, AppConfig.APPS[2]))

        AlertDialog.Builder(this, R.style.Theme_Portal_Dialog)
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
                loadLogo()
                toast("Pengaturan disimpan")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Org logo ─────────────────────────────────────────────────
    /** Load the backend logo into the header emblem; keeps the gold triangle on failure. */
    private fun loadLogo() {
        ImageLoader.load(binding.headerBar.orgEmblem, AppConfig.logoUrl(this), R.drawable.ic_org_triangle)
    }

    // ── Create site dialog ───────────────────────────────────────
    private fun setupCreateSite() {
        binding.headerBar.btnAddSite.setOnClickListener { showSiteDialog(null) }
        binding.headerBar.btnAddRegion.setOnClickListener { showRegionDialog(null) }
    }

    // ── Region create / edit / delete ────────────────────────────
    /** Long-press a region chip → Edit / Delete (only for real regions, not ALL/NONE). */
    private fun showRegionActions(code: String) {
        if (code == "ALL" || code == "NONE") return
        val region = vm.regions.value?.firstOrNull { it.regionCode == code } ?: return
        val options = arrayOf(getString(R.string.action_edit), getString(R.string.action_delete))
        AlertDialog.Builder(this, R.style.Theme_Portal_Dialog)
            .setTitle(region.regionName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRegionDialog(region)
                    1 -> confirmDeleteRegion(region)
                }
            }
            .show()
    }

    private fun confirmDeleteRegion(region: Region) {
        AlertDialog.Builder(this, R.style.Theme_Portal_Dialog)
            .setTitle(R.string.del_region_title)
            .setMessage(getString(R.string.del_region_msg, region.regionName))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                toast(getString(R.string.del_region_deleting))
                vm.deleteRegion(AppConfig.apiBase(this), region.regionCode) { ok, err ->
                    if (ok) toast(getString(R.string.del_region_success))
                    else toast("Gagal: ${err ?: "Unknown error"}")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Create ([existing] == null) or edit a region / "Wilayah". On edit the region
     * code is locked (immutable identifier) and fields are pre-filled.
     */
    private fun showRegionDialog(existing: Region?) {
        val pad = dp(20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }

        fun label(text: String) {
            container.addView(TextView(this).apply {
                this.text = text
                setTextColor(getColor(R.color.on_surface_variant))
                textSize = 12f
                setPadding(0, dp(12), 0, dp(4))
            })
        }

        fun field(labelText: String, hint: String?, caps: Boolean): EditText {
            label(labelText)
            val et = EditText(this).apply {
                if (hint != null) this.hint = hint
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    (if (caps) InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS else 0)
                setSingleLine()
            }
            applyInputStyle(et)
            container.addView(et, inputLayoutParams())
            return et
        }

        val codeEt = field(getString(R.string.cr_code), getString(R.string.cr_code_hint), caps = true)
        val nameEt = field(getString(R.string.cr_name), getString(R.string.cr_name_hint), caps = false)
        val descEt = field(getString(R.string.cr_desc), null, caps = false)

        if (existing != null) {
            codeEt.setText(existing.regionCode)
            codeEt.isEnabled = false
            nameEt.setText(existing.regionName)
            descEt.setText(existing.description ?: "")
        }

        val isEdit = existing != null
        val dialog = AlertDialog.Builder(this, R.style.Theme_Portal_Dialog)
            .setTitle(if (isEdit) R.string.edit_region_title else R.string.create_region_title)
            .setView(android.widget.ScrollView(this).apply { addView(container) })
            .setPositiveButton(if (isEdit) R.string.cr_save else R.string.cr_create, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = codeEt.text.toString().trim().uppercase()
                val name = nameEt.text.toString().trim()
                if (code.isEmpty() || name.isEmpty()) {
                    toast(getString(R.string.cr_required))
                    return@setOnClickListener
                }
                val desc = descEt.text.toString().trim().ifEmpty { null }

                val onResult: (Boolean, String?) -> Unit = { ok, err ->
                    if (ok) {
                        toast(getString(if (isEdit) R.string.cr_updated else R.string.cr_success))
                        dialog.dismiss()
                    } else {
                        toast("Gagal: ${err ?: "Unknown error"}")
                    }
                }

                if (isEdit) {
                    toast(getString(R.string.cr_saving))
                    vm.updateRegion(AppConfig.apiBase(this), existing!!.regionCode, name, desc, onResult)
                } else {
                    toast(getString(R.string.cr_creating))
                    vm.createRegion(AppConfig.apiBase(this), code, name, desc, onResult)
                }
            }
        }
        dialog.show()
    }

    // ── Long-press actions on a site tile (Edit / Delete) ────────
    private fun showSiteActions(site: Site) {
        val options = arrayOf(getString(R.string.action_edit), getString(R.string.action_delete))
        AlertDialog.Builder(this, R.style.Theme_Portal_Dialog)
            .setTitle(site.siteName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSiteDialog(site)
                    1 -> confirmDeleteSite(site)
                }
            }
            .show()
    }

    private fun confirmDeleteSite(site: Site) {
        AlertDialog.Builder(this, R.style.Theme_Portal_Dialog)
            .setTitle(R.string.del_site_title)
            .setMessage(getString(R.string.del_site_msg, site.siteName, site.siteCode))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                toast(getString(R.string.del_site_deleting))
                vm.deleteSite(AppConfig.apiBase(this), site.siteCode) { ok, err ->
                    if (ok) toast(getString(R.string.del_site_success))
                    else toast("Gagal: ${err ?: "Unknown error"}")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Create ([existing] == null) or edit an existing site. In edit mode the site
     * code is shown read-only (it's the immutable identifier) and the fields are
     * pre-filled from [existing].
     */
    private fun showSiteDialog(existing: Site?) {
        val pad = dp(20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }

        fun label(text: String) {
            container.addView(TextView(this).apply {
                this.text = text
                setTextColor(getColor(R.color.on_surface_variant))
                textSize = 12f
                setPadding(0, dp(12), 0, dp(4))
            })
        }

        fun field(labelText: String, hint: String?, caps: Boolean): EditText {
            label(labelText)
            val et = EditText(this).apply {
                if (hint != null) this.hint = hint
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    (if (caps) InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS else 0)
                setSingleLine()
            }
            applyInputStyle(et)
            container.addView(et, inputLayoutParams())
            return et
        }

        // Bold sub-heading that groups a block of fields (e.g. the IP allocation).
        fun sectionHeader(text: String) {
            container.addView(TextView(this).apply {
                this.text = text
                setTextColor(getColor(R.color.on_surface))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(4))
            })
        }

        // A labelled IP + Port row (IP stretches, Port is a fixed-width number field),
        // matching the desktop portal's "Alokasi IP Aplikasi" layout.
        fun ipRow(labelText: String): Pair<EditText, EditText> {
            label(labelText)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val ipEt = EditText(this).apply {
                hint = getString(R.string.cs_ip_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
            }
            val portEt = EditText(this).apply {
                hint = getString(R.string.cs_port_hint)
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine()
            }
            applyInputStyle(ipEt)
            applyInputStyle(portEt)
            row.addView(ipEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                portEt,
                LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = dp(8) }
            )
            container.addView(row, inputLayoutParams())
            return ipEt to portEt
        }

        val codeEt = field(getString(R.string.cs_code), getString(R.string.cs_code_hint), caps = true)
        val nameEt = field(getString(R.string.cs_name), null, caps = false)
        val blockIpEt = field(getString(R.string.cs_blockip), getString(R.string.cs_blockip_hint), caps = false)

        // Region dropdown: index 0 = no region (null), rest map to regions in order.
        val regions = vm.regions.value ?: emptyList()
        label(getString(R.string.cs_region))
        val regionNames = listOf(getString(R.string.cs_no_region)) + regions.map { it.regionName }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                regionNames
            )
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        container.addView(spinner, inputLayoutParams())

        // Image picker: optional photo for the site (multipart upload on save).
        label(getString(R.string.cs_image))
        var pickedImage: ImageUpload? = null
        val imageInfo = TextView(this).apply {
            text = if (existing?.imageUrl != null) getString(R.string.cs_image_current)
                   else getString(R.string.cs_image_none)
            setTextColor(getColor(R.color.on_surface_variant))
            textSize = 13f
            setPadding(0, 0, 0, dp(4))
        }
        val pickBtn = Button(this).apply {
            text = getString(R.string.cs_image_pick)
            setTextColor(getColor(R.color.primary))
            isAllCaps = false
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            stateListAnimator = null
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        container.addView(imageInfo)
        container.addView(pickBtn, inputLayoutParams())
        pickBtn.setOnClickListener {
            onImagePicked = { uri ->
                val img = readImage(uri)
                if (img == null) {
                    toast(getString(R.string.cs_image_error))
                } else {
                    pickedImage = img
                    imageInfo.text = getString(R.string.cs_image_selected, img.fileName, img.bytes.size / 1024)
                }
            }
            imagePicker.launch("image/*")
        }

        // Per-app IP allocation ("Alokasi IP Aplikasi"): IP + Port for each app,
        // sent as the `ips` array. App keys mirror the desktop portal exactly.
        sectionHeader(getString(R.string.cs_ip_section))
        val ipRows = listOf(
            "eyesee" to ipRow(getString(R.string.cs_ip_eyesee)),
            "bms" to ipRow(getString(R.string.cs_ip_bms)),
            "blm" to ipRow(getString(R.string.cs_ip_blm)),
            "vcom" to ipRow(getString(R.string.cs_ip_vcom))
        )

        val descEt = field(getString(R.string.cs_desc), null, caps = false)

        // Pre-fill for edit; the site code is the immutable identifier, so lock it.
        if (existing != null) {
            codeEt.setText(existing.siteCode)
            codeEt.isEnabled = false
            nameEt.setText(existing.siteName)
            blockIpEt.setText(existing.blockIp ?: "")
            descEt.setText(existing.description ?: "")
            // The /sites response carries regionCode (not regionId), so match on
            // either to preselect the site's current region in the dropdown.
            val regionPos = regions.indexOfFirst {
                (existing.regionId != null && it.id == existing.regionId) ||
                    (existing.regionCode != null && it.regionCode == existing.regionCode)
            }
            if (regionPos >= 0) spinner.setSelection(regionPos + 1)
            ipRows.forEach { (key, row) ->
                existing.ips.firstOrNull { it.appKey == key }?.let { ip ->
                    row.first.setText(ip.ip)
                    if (ip.port > 0) row.second.setText(ip.port.toString())
                }
            }
        }

        // The form is long (IP allocation adds 4 rows) — make the body scrollable
        // so the action buttons stay reachable on small screens.
        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        val isEdit = existing != null
        val dialog = AlertDialog.Builder(this, R.style.Theme_Portal_Dialog)
            .setTitle(if (isEdit) R.string.edit_site_title else R.string.create_site_title)
            .setView(scroll)
            .setPositiveButton(if (isEdit) R.string.cs_save else R.string.cs_create, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        // Override the positive click so validation can keep the dialog open.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = codeEt.text.toString().trim().uppercase()
                val name = nameEt.text.toString().trim()
                val blockIp = blockIpEt.text.toString().trim()
                if (code.isEmpty() || name.isEmpty() || blockIp.isEmpty()) {
                    toast(getString(R.string.cs_required))
                    return@setOnClickListener
                }
                val desc = descEt.text.toString().trim().ifEmpty { null }
                val pos = spinner.selectedItemPosition
                val regionId = if (pos <= 0) null else regions[pos - 1].id

                // Only rows with an IP are sent; a blank port goes up as null.
                val ips = ipRows.mapNotNull { (key, row) ->
                    val ip = row.first.text.toString().trim()
                    if (ip.isEmpty()) null
                    else SiteIpInput(key, ip, row.second.text.toString().trim().toIntOrNull())
                }

                val onResult: (Boolean, String?) -> Unit = { ok, err ->
                    if (ok) {
                        toast(getString(if (isEdit) R.string.cs_updated else R.string.cs_success))
                        dialog.dismiss()
                    } else {
                        toast("Gagal: ${err ?: "Unknown error"}")
                    }
                }

                if (isEdit) {
                    toast(getString(R.string.cs_saving))
                    vm.updateSite(
                        AppConfig.apiBase(this), existing!!.siteCode, name, blockIp, desc, regionId, ips, pickedImage, onResult
                    )
                } else {
                    toast(getString(R.string.cs_creating))
                    vm.createSite(
                        AppConfig.apiBase(this), code, name, blockIp, desc, regionId, ips, pickedImage, onResult
                    )
                }
            }
        }
        dialog.show()
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
        val filtered = vm.visibleSites()
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

        // Empty state only after a successful load. Tailor the message so a
        // no-match search reads differently from a genuinely empty region.
        val showEmpty = vm.phase.value == PortalViewModel.Phase.CONTENT && filtered.isEmpty()
        binding.emptyView.emptyDesc.setText(
            if (vm.searchQuery.isNotBlank()) R.string.empty_search_desc else R.string.empty_desc
        )
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
    /** Read a picked image content URI into bytes + filename + MIME for upload. */
    private fun readImage(uri: Uri): ImageUpload? = try {
        val cr = contentResolver
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.isEmpty()) null
        else {
            val mime = cr.getType(uri) ?: "image/jpeg"
            var name = "image"
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { name = it }
            }
            ImageUpload(bytes, name, mime)
        }
    } catch (e: Exception) {
        null
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    /** Give a programmatic EditText the app's outlined-input look (rounded dark field). */
    private fun applyInputStyle(et: EditText) {
        et.setTextColor(getColor(R.color.on_surface))
        et.setHintTextColor(getColor(R.color.on_surface_variant_60))
        et.background = ContextCompat.getDrawable(this, R.drawable.bg_input)
        et.setPadding(dp(12), dp(10), dp(12), dp(10))
        et.textSize = 14f
    }

    /** Match-parent layout params with a small bottom gap, for stacked dialog inputs. */
    private fun inputLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) }

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
