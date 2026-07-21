package com.thor.smartwall

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import com.thor.smartwall.Prefs.giphyApiKey
import com.thor.smartwall.Prefs.gifUri
import com.thor.smartwall.Prefs.mode
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Search GIPHY's free public library from inside the app and set the result straight as your
 * GIF wallpaper. Note: Tenor (Google's GIF API) stopped issuing new developer API keys in
 * January 2026, so GIPHY is the one that's actually reachable for a new project right now.
 *
 * You need your own free GIPHY API key (developers.giphy.com -> Create an App -> select "API").
 * Their beta key works fine to start (rate-limited to 100 calls/hour, plenty for personal use).
 */
class GifSearchActivity : AppCompatActivity() {

    private val ioExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var resultsGrid: GridLayout
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#111318"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = getString(R.string.gif_search_title)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
        }
        root.addView(title)

        val attribution = TextView(this).apply {
            text = getString(R.string.powered_by_giphy)
            setTextColor(android.graphics.Color.parseColor("#7C4DFF"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(attribution)

        val searchView = SearchView(this).apply {
            queryHint = getString(R.string.search_gifs_hint)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    if (!query.isNullOrBlank()) runSearch(query)
                    return true
                }
                override fun onQueryTextChange(newText: String?) = false
            })
        }
        root.addView(searchView)

        statusText = TextView(this).apply {
            setTextColor(android.graphics.Color.parseColor("#9AA0AC"))
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusText)

        resultsGrid = GridLayout(this).apply {
            columnCount = 3
        }
        val scroll = ScrollView(this).apply {
            addView(resultsGrid)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)

        if (applicationContext.giphyApiKey.isNullOrBlank()) {
            promptForApiKey()
        }
    }

    private fun promptForApiKey() {
        val input = EditText(this).apply {
            hint = getString(R.string.paste_giphy_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.need_giphy_key_title)
            .setMessage(R.string.need_giphy_key_message)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    applicationContext.giphyApiKey = key
                    Toast.makeText(this, R.string.key_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.open_signup_page) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.giphy.com/dashboard/")))
                } catch (_: Exception) { /* no browser available - nothing more we can do here */ }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runSearch(query: String) {
        val key = applicationContext.giphyApiKey
        if (key.isNullOrBlank()) {
            promptForApiKey()
            return
        }
        resultsGrid.removeAllViews()
        statusText.setText(R.string.searching)

        ioExecutor.execute {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL(
                    "https://api.giphy.com/v1/gifs/search?api_key=$key&q=$encoded&limit=24&rating=pg-13"
                )
                val text = fetchText(url)
                val data = JSONObject(text).getJSONArray("data")

                val previewUrls = mutableListOf<Pair<String, String>>() // preview url to full-res url
                for (i in 0 until data.length()) {
                    val images = data.getJSONObject(i).getJSONObject("images")
                    val preview = images.optJSONObject("fixed_width_small")
                        ?: images.optJSONObject("fixed_height_small")
                        ?: continue
                    val original = images.optJSONObject("original") ?: continue
                    previewUrls.add(preview.getString("url") to original.getString("url"))
                }

                mainHandler.post {
                    statusText.text = if (previewUrls.isEmpty())
                        getString(R.string.no_results) else ""
                    for ((previewUrl, fullUrl) in previewUrls) {
                        addResultTile(previewUrl, fullUrl)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    statusText.text = getString(R.string.search_failed, e.message ?: "")
                }
            }
        }
    }

    private fun addResultTile(previewUrl: String, fullUrl: String) {
        val imageView = ImageView(this).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = dp(100)
                height = dp(100)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.parseColor("#22262E"))
            setOnClickListener { downloadAndUse(fullUrl) }
        }
        resultsGrid.addView(imageView)

        ioExecutor.execute {
            try {
                val bytes = fetchBytes(URL(previewUrl))
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                mainHandler.post { imageView.setImageBitmap(bmp) }
            } catch (_: Exception) {
                // Thumbnail failed to load - tile just stays blank, not worth interrupting the grid for.
            }
        }
    }

    private fun downloadAndUse(fullUrl: String) {
        Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            try {
                val bytes = fetchBytes(URL(fullUrl))
                val dir = File(filesDir, "downloaded_gifs")
                dir.mkdirs()
                val file = File(dir, "gif_${System.currentTimeMillis()}.gif")
                FileOutputStream(file).use { it.write(bytes) }

                applicationContext.gifUri = Uri.fromFile(file).toString()
                applicationContext.mode = WallMode.GIF

                mainHandler.post {
                    Toast.makeText(this, R.string.gif_ready_toast, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchText(url: URL): String {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.inputStream.use { return it.bufferedReader().readText() }
    }

    private fun fetchBytes(url: URL): ByteArray {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.inputStream.use { return it.readBytes() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
}
