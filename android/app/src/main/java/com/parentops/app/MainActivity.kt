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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.time.Instant
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var data by mutableStateOf(AppData(), neverEqualPolicy())
    private var syncing by mutableStateOf(false)
    private var pendingShare by mutableStateOf<String?>(null)

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
        handleShare(intent)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF4653E8),
                surface = Color(0xFFFFFFFF),
                background = Color(0xFFF4F5F7))) {
                App(
                    data = data,
                    syncing = syncing,
                    pendingShare = pendingShare,
                    onMutate = { block ->
                        block(data)
                        Store.save(this, data)
                        data = data
                    },
                    onAddChild = { signInLauncher.launch(GoogleAuthHelper.signInIntent(this)) },
                    onSync = { runSync() },
                    onIngestShare = { childEmail -> ingestShare(childEmail) },
                    onDismissShare = { pendingShare = null },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A screenshot or text shared into the app becomes extractable content —
     *  the no-Google ingestion path (school admin blocks API access). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { pendingShare = it }
            return
        }
        if (intent.type?.startsWith("image/") == true) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri == null) return
            try {
                val image = InputImage.fromFilePath(this, uri)
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image)
                    .addOnSuccessListener { result ->
                        if (result.text.isBlank()) {
                            Toast.makeText(this, "Couldn't read any text in that image",
                                Toast.LENGTH_LONG).show()
                        } else {
                            pendingShare = result.text
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Text recognition failed", Toast.LENGTH_LONG).show()
                    }
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open the shared image", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun ingestShare(childEmail: String?) {
        val text = pendingShare ?: return
        pendingShare = null
        // Works with zero children: create a manual child on the fly.
        val child = data.children.find { it.email == childEmail }
            ?: data.children.firstOrNull()
            ?: Child(email = "manual-family", name = "Family",
                     color = CHILD_COLORS[0]).also { data.children.add(it) }
        val key = "shared|${System.currentTimeMillis()}"
        val post = Post(
            key = key, childEmail = child.email, kind = "shared",
            courseName = "Shared",
            title = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)
                ?: "Shared note",
            body = text, postedAt = Instant.now().toString())
        data.posts.add(0, post)
        val extracted = Extract.fromPost(post, child)
        if (extracted.isEmpty()) {
            // Never silently drop a share — keep it visible even unparsed.
            data.items.add(ActionItem(
                id = "$key#0", childEmail = child.email, postKey = key,
                title = post.title,
                detail = "Saved from share — no date/action detected, check the original."))
        } else {
            data.items.addAll(extracted)
        }
        Store.save(this, data)
        data = data
        Toast.makeText(this, "Added to ParentOps ✓", Toast.LENGTH_SHORT).show()
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
