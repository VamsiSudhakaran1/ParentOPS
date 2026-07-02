package com.parentops.app

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

/**
 * Native Google sign-in with read-only Classroom scopes. Requires an
 * "Android" OAuth client in Google Cloud console registered with this app's
 * package name and the shared keystore's SHA-1 (see README) — no client
 * secret ships in the app.
 */
object GoogleAuthHelper {

    val SCOPES = listOf(
        "https://www.googleapis.com/auth/classroom.courses.readonly",
        "https://www.googleapis.com/auth/classroom.announcements.readonly",
        "https://www.googleapis.com/auth/classroom.coursework.me.readonly",
        "https://www.googleapis.com/auth/classroom.courseworkmaterials.readonly",
    )

    fun signInIntent(activity: Activity): Intent {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        SCOPES.forEach { builder.requestScopes(Scope(it)) }
        val client = GoogleSignIn.getClient(activity, builder.build())
        // Sign out first so the account picker always appears — needed to add
        // a second child instead of silently reusing the last account.
        client.signOut()
        return client.signInIntent
    }

    fun accountFromResult(data: Intent?): GoogleSignInAccount? = try {
        GoogleSignIn.getSignedInAccountFromIntent(data)
            .getResult(ApiException::class.java)
    } catch (e: ApiException) {
        null
    }

    /**
     * Fresh access token for one child's account. Must be called off the main
     * thread. Throws UserRecoverableAuthException when the user needs to
     * re-approve — the caller shows its intent.
     */
    fun token(ctx: Context, email: String): String {
        val scopeString = "oauth2:" + SCOPES.joinToString(" ")
        return GoogleAuthUtil.getToken(ctx, Account(email, "com.google"), scopeString)
    }

    class NeedsApproval(val intent: Intent) : Exception()

    fun tokenOrApproval(ctx: Context, email: String): String = try {
        token(ctx, email)
    } catch (e: UserRecoverableAuthException) {
        throw e.intent?.let { NeedsApproval(it) } ?: e
    }
}
