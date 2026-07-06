package com.wireguard.android.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.wireguard.android.R

class SecureBrowserWidgetSearchActivity : AppCompatActivity() {
    private lateinit var searchInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.secure_browser_widget_search_activity)
        searchInput = findViewById(R.id.search_input)
        findViewById<TextView>(R.id.search_button).setOnClickListener { submitSearch() }
        searchInput.setOnEditorActionListener { _, _, _ ->
            submitSearch()
            true
        }
        searchInput.requestFocus()
        searchInput.post {
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun submitSearch() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val intent = Intent(this, SecureBrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (query.isNotBlank()) {
            intent.putExtra(SecureBrowserActivity.EXTRA_INITIAL_URL, googleSearchUrl(query))
        }
        startActivity(intent)
        finish()
    }

    private fun googleSearchUrl(query: String): String =
        Uri.Builder()
            .scheme("https")
            .authority("www.google.com")
            .path("search")
            .appendQueryParameter("q", query)
            .build()
            .toString()
}
