package com.cyj265.iptvplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.cyj265.iptvplayer.data.HttpLoader
import com.cyj265.iptvplayer.data.PlaylistParser
import com.cyj265.iptvplayer.data.PlaylistRepository
import com.cyj265.iptvplayer.databinding.ActivitySettingsBinding
import java.nio.charset.Charset

/**
 * 设置：播放列表 URL / 本地文件导入 / EPG / 直播流链接。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_PLAYLIST = "playlist"
        const val ACTION_DIRECT = "direct"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: PlaylistRepository

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importLocalFile(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = PlaylistRepository(this)

        binding.inputPlaylistUrl.setText(repository.playlistUrl.orEmpty())
        binding.inputEpgUrl.setText(repository.epgUrl.orEmpty())

        binding.btnLoad.setOnClickListener { saveAndLoadPlaylist() }
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnImportFile.setOnClickListener {
            openDocument.launch(arrayOf("*/*"))
        }
        binding.btnPlayDirect.setOnClickListener { playDirect() }
    }

    private fun saveAndLoadPlaylist() {
        val url = binding.inputPlaylistUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
            return
        }
        repository.playlistUrl = url
        repository.epgUrl = binding.inputEpgUrl.text.toString().trim().ifEmpty { null }
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_ACTION, ACTION_PLAYLIST)
        )
        finish()
    }

    private fun playDirect() {
        val url = binding.inputDirectUrl.text.toString().trim()
        if (!url.startsWith("http")) {
            Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
            return
        }
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_ACTION, ACTION_DIRECT)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_NAME, url)
        )
        finish()
    }

    private fun importLocalFile(uri: Uri) {
        binding.tvLoadStatus.text = getString(R.string.importing)
        Thread {
            try {
                val content = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (content == null) {
                    runOnUiThread {
                        binding.tvLoadStatus.text = getString(R.string.load_failed)
                    }
                    return@Thread
                }
                // 尝试 UTF-8 / GBK
                var text = String(content, Charsets.UTF_8)
                if (text.contains('\uFFFD')) {
                    text = String(content, Charset.forName("GBK"))
                }
                val channels = PlaylistParser.parseAuto(text)
                if (channels.isEmpty()) {
                    runOnUiThread {
                        binding.tvLoadStatus.text = getString(R.string.no_channels)
                    }
                    return@Thread
                }
                repository.playlistUrl = null
                repository.saveChannels(channels)
                runOnUiThread {
                    setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(EXTRA_ACTION, ACTION_PLAYLIST)
                    )
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvLoadStatus.text = getString(R.string.load_failed)
                }
            }
        }.start()
    }
}
