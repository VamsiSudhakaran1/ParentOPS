package com.parentops.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var data by mutableStateOf(AppData(), neverEqualPolicy())
    private var syncing by mutableStateOf(false)

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { res ->
        val account = GoogleAuthHelper.accountFromResult(res.data)
        if (account?.email != null) {
            val email = account.email!!
            if (data.children.none { it.email == email }) {
                data.children.add(Child(
                    email = email,
                    name = account.displayName ?: email.substringBefore("@"),
                    color = CHILD_COLORS[data.children.size % CHILD_COLORS.size]))
            }
            Store.save(this, data)
            data = data
            runSync()
        } else {
            Toast.makeText(this, "Sign-in was cancelled or blocked", Toast.LENGTH_LONG).show()
        }
    }

    private val approvalLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { runSync() }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        data = Store.load(this)
        SyncWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF4653E8),
                surface = Color(0xFFFFFFFF),
                background = Color(0xFFF4F5F7))) {
                App(
                    data = data,
                    syncing = syncing,
                    onMutate = { block ->
                        block(data)
                        Store.save(this, data)
                        data = data
                    },
                    onAddChild = { signInLauncher.launch(GoogleAuthHelper.signInIntent(this)) },
                    onSync = { runSync() },
                    onOpenLink = { url ->
                        if (!url.isNullOrBlank()) {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (e: Exception) { /* no browser */ }
                        }
                    })
            }
        }
    }

    private fun runSync() {
        if (syncing || data.children.isEmpty()) return
        syncing = true
        lifecycleScope.launch {
            try {
                val (newPosts, errors) = SyncManager.sync(this@MainActivity, data)
                val msg = if (errors.isEmpty()) "Synced — $newPosts new posts"
                    else "Synced with errors: ${errors.first()}"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            } catch (e: GoogleAuthHelper.NeedsApproval) {
                approvalLauncher.launch(e.intent)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity,
                    "Sync failed: ${e.message?.take(120)}", Toast.LENGTH_LONG).show()
            }
            data = data
            syncing = false
        }
    }
}
