/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CloudServer
import org.fcitx.fcitx5.android.engine.remote.CloudConfig
import org.fcitx.fcitx5.android.engine.remote.HttpRemoteModel
import org.fcitx.fcitx5.android.engine.remote.ServerAddress
import org.fcitx.fcitx5.android.engine.remote.ServerKey
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.toast
import java.io.IOException
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * The cloud build's settings for the user's own server ([CloudServer] keeps them). Turning it on
 * says what will be sent and where and, over HTTPS, shows the key the server presents for the
 * user to compare with what it printed; only on a yes is that key trusted and anything sent.
 */
class CloudSettingsFragment : PaddingPreferenceFragment() {

    private val cloud = CloudServer.instance
    private lateinit var enabled: SwitchPreferenceCompat
    private lateinit var server: EditTextPreference
    private lateinit var token: EditTextPreference
    private lateinit var trusted: Preference
    private var fetching: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        // what is shown comes from CloudServer, and only the listeners below write to it
        preferenceManager.preferenceDataStore = object : PreferenceDataStore() {}
        enabled = SwitchPreferenceCompat(context).apply {
            key = "cloud_enabled"
            setTitle(R.string.cloud_enabled)
            setSummary(R.string.cloud_enabled_summary)
            setOnPreferenceChangeListener { _, on ->
                if (on == true) turnOn() else update(cloud.config().off())
                false
            }
        }
        server = text("cloud_server", R.string.cloud_server, secret = false) { address ->
            val problem = if (address.isBlank()) null else problem(address)
            if (problem != null) requireContext().toast(problem) else update(cloud.config().withServer(address))
        }
        token = text("cloud_token", R.string.cloud_token, secret = true) { value ->
            if (CloudConfig.tokenValid(value.trim())) update(cloud.config().withToken(value))
            else requireContext().toast(R.string.cloud_token_invalid)
        }
        trusted = Preference(context).apply {
            key = "cloud_key"
            setTitle(R.string.cloud_key)
            isSelectable = false
        }
        preferenceScreen = preferenceManager.createPreferenceScreen(context).apply {
            listOf(enabled, server, token, trusted).forEach {
                it.isIconSpaceReserved = false
                it.isSingleLineTitle = false
                it.isPersistent = false
                addPreference(it)
            }
        }
        show(cloud.config())
    }

    // a key, though nothing is stored by it: the edit dialog finds its preference by key
    private fun text(name: String, title: Int, secret: Boolean, set: (String) -> Unit) = EditTextPreference(preferenceManager.context).apply {
        key = name
        setDefaultValue("")
        setTitle(title)
        dialogTitle = getString(title)
        setOnBindEditTextListener {
            it.isSingleLine = true
            if (secret) it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        setOnPreferenceChangeListener { _, value ->
            set(value as String)
            false
        }
    }

    private fun update(config: CloudConfig) {
        try {
            cloud.save(config)
        } catch (e: IOException) {
            requireContext().toast(e)
            return
        }
        show(config)
    }

    private fun show(config: CloudConfig) {
        enabled.isChecked = config.enabled
        server.text = config.server
        server.summary = config.server.ifEmpty { getString(R.string.not_set) }
        token.text = config.token
        token.summary = getString(if (config.token.isEmpty()) R.string.not_set else R.string.set_hidden)
        trusted.summary = if (config.key.isEmpty()) getString(R.string.cloud_key_none) else ServerKey.grouped(config.key)
    }

    private fun turnOn() {
        val config = cloud.config()
        val address = config.server
        val url = (ServerAddress.check(address) as? ServerAddress.Valid)?.url ?: run {
            requireContext().toast(problem(address) ?: R.string.cloud_server_missing)
            return
        }
        // the handshake only, nothing sent: the key the server presents, for the user to compare
        if (fetching?.isActive == true) return
        fetching = viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { ServerKey.fetch(url, FETCH_TIMEOUT) } }
                .onSuccess { confirm(address, it) }
                .onFailure { requireContext().toast(getString(R.string.cloud_server_unreachable, it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun confirm(address: String, serverKey: String) {
        val before = cloud.config().key.takeIf { it.isNotEmpty() && it != serverKey }
        val message = listOfNotNull(
            getString(R.string.cloud_confirm_message, address),
            getString(R.string.cloud_confirm_key, ServerKey.grouped(serverKey)),
            before?.let { getString(R.string.cloud_key_changed, ServerKey.grouped(it)) },
        ).joinToString("\n\n")
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.cloud_confirm_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // the address it was asked for, still: not one changed meanwhile
                val now = cloud.config()
                if (now.server != address) {
                    requireContext().toast(R.string.cloud_server_changed)
                    return@setPositiveButton
                }
                update(now.on(serverKey))
                check()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Asks the server, with the token, what it runs: a wrong token would otherwise only ever be a server that says nothing. */
    private fun check() {
        val target = cloud.config().target() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { HttpRemoteModel(target.url, target.token, { it.run() }, target.key, FETCH_TIMEOUT).health() }
            }
                .onSuccess { requireContext().toast(getString(R.string.cloud_connected, it)) }
                .onFailure {
                    requireContext().toast(
                        if ((it as? HttpRemoteModel.StatusException)?.code == HTTP_UNAUTHORIZED) getString(R.string.cloud_token_rejected)
                        else getString(R.string.cloud_check_failed, it.message ?: it.javaClass.simpleName)
                    )
                }
        }
    }

    private fun problem(address: String): Int? = if (address.isBlank()) R.string.cloud_server_missing else when (ServerAddress.check(address)) {
        is ServerAddress.Valid -> null
        ServerAddress.Invalid.MALFORMED -> R.string.cloud_server_malformed
        ServerAddress.Invalid.SCHEME -> R.string.cloud_server_scheme
        ServerAddress.Invalid.NOT_HTTPS, ServerAddress.Invalid.NOT_LOOPBACK -> R.string.cloud_server_not_https
    }

    private companion object {
        const val FETCH_TIMEOUT = 5000
    }
}
