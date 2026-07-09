package com.nexxuz.portal

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexxuz.portal.data.ApiClient
import com.nexxuz.portal.data.ImageUpload
import com.nexxuz.portal.data.Region
import com.nexxuz.portal.data.Site
import com.nexxuz.portal.data.SiteIpInput
import com.nexxuz.portal.data.StatusChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PortalViewModel : ViewModel() {

    enum class Phase { LOADING, CONTENT, ERROR }

    val regions = MutableLiveData<List<Region>>(emptyList())
    val sites = MutableLiveData<List<Site>>(emptyList())
    /** siteCode -> online?  (absent = still checking) */
    val statuses = MutableLiveData<Map<String, Boolean>>(emptyMap())
    val phase = MutableLiveData(Phase.LOADING)
    val errorMessage = MutableLiveData<String?>(null)
    val lastCheck = MutableLiveData<String?>(null)

    /** Selected region code, "ALL", or "NONE". */
    var selectedRegion: String = "ALL"
    /** Free-text site search (matches site name or code); empty = no filter. */
    var searchQuery: String = ""
    /** Index into AppConfig.APPS (VCOMM/BMS/BLM). */
    var selectedAppIndex: Int = 0

    private var loaded = false
    private var pollJob: Job? = null
    private var apiBase: String = ""

    fun ensureLoaded(apiBaseUrl: String) {
        if (loaded && apiBase == apiBaseUrl) return
        reload(apiBaseUrl)
    }

    fun reload(apiBaseUrl: String) {
        apiBase = apiBaseUrl
        loaded = true
        phase.value = Phase.LOADING
        errorMessage.value = null
        val api = ApiClient(apiBaseUrl)
        viewModelScope.launch {
            try {
                // coroutineScope contains a failure of either async and rethrows
                // it here so the catch below handles it. With a plain run{} the
                // asyncs are children of viewModelScope and an unreachable master
                // IP would crash the app instead of surfacing as an error state.
                val (regionList, siteList) = coroutineScope {
                    val rDef = async { api.getRegions() }
                    val sDef = async { api.getSites() }
                    rDef.await() to sDef.await()
                }

                // Resolve each site's region code (object -> regionId -> fallback).
                val byId = regionList.associateBy { it.id }
                siteList.forEach { s ->
                    if (s.regionCode == null && s.regionId != null) {
                        s.regionCode = byId[s.regionId]?.regionCode
                    }
                }

                regions.value = regionList
                sites.value = siteList
                phase.value = Phase.CONTENT
                startPolling()
            } catch (e: Exception) {
                errorMessage.value = e.message ?: "Network error"
                phase.value = Phase.ERROR
            }
        }
    }

    /**
     * Create a new site, then reload the list on success. [onResult] is invoked on
     * the main thread with (success, errorMessage) so the caller can toast/close.
     */
    fun createSite(
        apiBaseUrl: String,
        siteCode: String,
        siteName: String,
        blockIp: String,
        description: String?,
        regionId: Int?,
        ips: List<SiteIpInput>,
        image: ImageUpload?,
        onResult: (Boolean, String?) -> Unit
    ) {
        val api = ApiClient(apiBaseUrl)
        viewModelScope.launch {
            try {
                if (image != null) {
                    api.createSiteWithImage(siteCode, siteName, blockIp, description, regionId, ips, image)
                } else {
                    api.createSite(siteCode, siteName, blockIp, description, regionId, ips)
                }
                onResult(true, null)
                reload(apiBaseUrl)
            } catch (e: Exception) {
                onResult(false, e.message ?: "Network error")
            }
        }
    }

    /**
     * Update an existing site, then reload the list on success. [onResult] runs on
     * the main thread with (success, errorMessage).
     */
    fun updateSite(
        apiBaseUrl: String,
        siteCode: String,
        siteName: String,
        blockIp: String,
        description: String?,
        regionId: Int?,
        ips: List<SiteIpInput>,
        image: ImageUpload?,
        onResult: (Boolean, String?) -> Unit
    ) {
        val api = ApiClient(apiBaseUrl)
        viewModelScope.launch {
            try {
                api.updateSite(siteCode, siteName, blockIp, description, regionId, ips)
                // Image goes up as a separate multipart PUT (the API keeps other fields).
                if (image != null) api.uploadSiteImage(siteCode, image)
                onResult(true, null)
                reload(apiBaseUrl)
            } catch (e: Exception) {
                onResult(false, e.message ?: "Network error")
            }
        }
    }

    /** Create a region, then reload on success. [onResult] runs on the main thread. */
    fun createRegion(
        apiBaseUrl: String,
        regionCode: String,
        regionName: String,
        description: String?,
        onResult: (Boolean, String?) -> Unit
    ) {
        val api = ApiClient(apiBaseUrl)
        viewModelScope.launch {
            try {
                api.createRegion(regionCode, regionName, description)
                onResult(true, null)
                reload(apiBaseUrl)
            } catch (e: Exception) {
                onResult(false, e.message ?: "Network error")
            }
        }
    }

    /** Update an existing region, then reload on success. */
    fun updateRegion(
        apiBaseUrl: String,
        regionCode: String,
        regionName: String,
        description: String?,
        onResult: (Boolean, String?) -> Unit
    ) {
        val api = ApiClient(apiBaseUrl)
        viewModelScope.launch {
            try {
                api.updateRegion(regionCode, regionName, description)
                onResult(true, null)
                reload(apiBaseUrl)
            } catch (e: Exception) {
                onResult(false, e.message ?: "Network error")
            }
        }
    }

    /** Delete a region by code, then reload on success. */
    fun deleteRegion(
        apiBaseUrl: String,
        regionCode: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        val api = ApiClient(apiBaseUrl)
        viewModelScope.launch {
            try {
                api.deleteRegion(regionCode)
                // If the deleted region was selected, fall back to ALL so the grid
                // doesn't filter on a code that no longer exists.
                if (selectedRegion == regionCode) selectedRegion = "ALL"
                onResult(true, null)
                reload(apiBaseUrl)
            } catch (e: Exception) {
                onResult(false, e.message ?: "Network error")
            }
        }
    }

    /** Delete a site by code, then reload the list on success. */
    fun deleteSite(
        apiBaseUrl: String,
        siteCode: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        val api = ApiClient(apiBaseUrl)
        viewModelScope.launch {
            try {
                api.deleteSite(siteCode)
                onResult(true, null)
                reload(apiBaseUrl)
            } catch (e: Exception) {
                onResult(false, e.message ?: "Network error")
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                try {
                    checkAll()
                } catch (e: Exception) {
                    // A status sweep failing should never take the app down;
                    // skip this round and try again on the next tick.
                }
                delay(30_000)
            }
        }
    }

    private suspend fun checkAll() {
        val current = sites.value ?: return
        val appKey = appKeyForIndex(selectedAppIndex)
        // coroutineScope so the per-site checks are children of this call and a
        // failure is contained here (and caught by the polling loop) rather than
        // propagating to viewModelScope.
        val results = coroutineScope {
            current.map { site ->
                async {
                    val ipInfo = site.bestIp(appKey)
                    val online = if (ipInfo == null) false
                    else StatusChecker.isOnline(ipInfo.ip, ipInfo.port)
                    site.siteCode to online
                }
            }.awaitAll()
        }
        statuses.value = results.toMap()
        lastCheck.value = nowHms()
    }

    private fun appKeyForIndex(index: Int): String =
        com.nexxuz.portal.config.AppConfig.APPS.getOrNull(index)?.apiKey ?: "chat"

    private fun nowHms(): String {
        val cal = java.util.Calendar.getInstance()
        return String.format(
            "%02d:%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }

    fun sitesForSelectedRegion(): List<Site> {
        val all = sites.value ?: return emptyList()
        return when (selectedRegion) {
            "ALL" -> all
            "NONE" -> all.filter { it.regionCode == null }
            else -> all.filter { it.regionCode == selectedRegion }
        }
    }

    /** Sites shown in the grid: region filter, then the free-text search (name or code). */
    fun visibleSites(): List<Site> {
        val byRegion = sitesForSelectedRegion()
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) return byRegion
        return byRegion.filter {
            it.siteName.lowercase().contains(q) || it.siteCode.lowercase().contains(q)
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
