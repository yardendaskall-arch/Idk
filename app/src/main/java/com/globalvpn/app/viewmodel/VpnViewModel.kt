package com.globalvpn.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.globalvpn.app.data.Country
import com.globalvpn.app.data.ServerRepository
import com.globalvpn.app.data.VpnServer
import com.globalvpn.app.data.WarpCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VpnViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServerRepository(app)

    private val _countries = MutableLiveData<List<Country>>(emptyList())
    val countries: LiveData<List<Country>> get() = _countries

    private val _selectedServer = MutableLiveData<VpnServer?>(null)
    val selectedServer: LiveData<VpnServer?> get() = _selectedServer

    private val _credentials = MutableLiveData<WarpCredentials?>()
    val credentials: LiveData<WarpCredentials?> get() = _credentials

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> get() = _loading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> get() = _error

    private val _activeConfig = MutableLiveData<String?>(null)
    val activeConfig: LiveData<String?> get() = _activeConfig

    init {
        loadCountries()
        loadCredentials()
    }

    private fun loadCountries() {
        _countries.value = repo.getCountries()
    }

    private fun loadCredentials() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val creds = if (repo.hasSavedCredentials()) {
                    repo.getSavedCredentials()
                } else {
                    null
                }
                _credentials.postValue(creds)
            } catch (e: Exception) {
                _error.postValue(e.message)
            }
        }
    }

    fun selectServer(server: VpnServer) {
        _selectedServer.value = server
    }

    fun prepareVpnConfig(onReady: (config: String, countryName: String) -> Unit) {
        _loading.value = true
        viewModelScope.launch {
            try {
                val creds = repo.getOrRegisterCredentials()
                _credentials.postValue(creds)
                val config = repo.buildConfigString(creds)
                _activeConfig.postValue(config)
                val country = _selectedServer.value?.countryName ?: "Global"
                onReady(config, country)
            } catch (e: Exception) {
                _error.postValue("Failed to get VPN credentials: ${e.message}")
            } finally {
                _loading.postValue(false)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
