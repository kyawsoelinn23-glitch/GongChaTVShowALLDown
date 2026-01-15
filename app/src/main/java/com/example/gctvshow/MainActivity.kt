package com.example.gctvshow

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.gctvshow.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var btnDownload: android.widget.Button
    private lateinit var btnPlay: android.widget.Button
    private lateinit var btnRefresh: android.widget.Button
    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var spinnerVideos: android.widget.Spinner

    private var player: ExoPlayer? = null
    private val downloadIds = mutableMapOf<String, Long>() // Store download IDs
    private val videoFiles = mutableListOf<File>() // Store all video files
    private var selectedVideoFile: File? = null // Currently selected video

    private val TAG = "VideoApp"

    // List of video URLs and titles
    private val videoList = listOf(
        VideoItem(
            "Gong cha TV Show 1",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/Zeitgeist/Zeitgeist%202010_%20Year%20in%20Review.mp4"
        ),
        VideoItem(
            "Gong cha TV Show 2",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/Demo%20Slam/Google%20Demo%20Slam_%2020ft%20Search.mp4"
        ),
        VideoItem(
            "Gong cha TV Show 3",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/April%20Fool's%202013/Introducing%20Gmail%20Blue.mp4"
        ),
        VideoItem(
            "Gong cha TV Show 4",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/April%20Fool's%202013/Introducing%20Google%20Fiber%20to%20the%20Pole.mp4"
        )
    )

    data class VideoItem(val title: String, val url: String)

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "=== DOWNLOAD BROADCAST RECEIVED ===")

            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1
            Log.d(TAG, "Completed download ID: $id")

            // Find which video this ID belongs to
            val videoTitle = downloadIds.entries.find { it.value == id }?.key

            if (videoTitle != null) {
                Log.d(TAG, "✅ Download completed for: $videoTitle")
                runOnUiThread {
                    showToast("✅ $videoTitle download complete!")
                    checkForDownloadedFiles()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize views
        btnDownload = findViewById(R.id.btnDownload)
        btnPlay = findViewById(R.id.btnPlay)
        btnRefresh = findViewById(R.id.btnRefresh)
        playerView = findViewById(R.id.playerView)
        spinnerVideos = findViewById(R.id.spinnerVideos)

        Log.d(TAG, "=== APP STARTED ===")

        btnPlay.isEnabled = false
        playerView.visibility = View.GONE
        btnPlay.text = "SELECT A VIDEO"

        setupUI()
        setupClickListeners()
        registerDownloadReceiver()

        // Check for existing files on start
        checkForDownloadedFiles()
    }

    private fun setupUI() {
        // Setup spinner with video titles
        val videoTitles = videoList.map { it.title }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, videoTitles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVideos.adapter = adapter

        spinnerVideos.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedVideo = videoList[position]
                Log.d(TAG, "Selected video: ${selectedVideo.title}")
                updatePlayButtonState()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // Do nothing
            }
        }
    }

    private fun setupClickListeners() {
        // Download selected video
        btnDownload.setOnClickListener {
            val position = spinnerVideos.selectedItemPosition
            if (position != AdapterView.INVALID_POSITION) {
                val video = videoList[position]
                Log.d(TAG, "=== DOWNLOAD BUTTON CLICKED: ${video.title} ===")
                downloadVideo(video)
            }
        }

        // Download all videos
        binding.btnDownloadAll.setOnClickListener {
            Log.d(TAG, "=== DOWNLOAD ALL VIDEOS ===")
            downloadAllVideos()
        }

        // Play selected video
        btnPlay.setOnClickListener {
            Log.d(TAG, "=== PLAY BUTTON CLICKED ===")
            playVideo()
        }

        // Refresh files list
        btnRefresh.setOnClickListener {
            Log.d(TAG, "=== REFRESH BUTTON CLICKED ===")
            checkForDownloadedFiles()
        }
    }

    private fun downloadVideo(video: VideoItem) {
        Log.d(TAG, "Downloading: ${video.title}")

        val fileName = "${video.title.replace(" ", "_")}.mp4"
        showToast("Starting download: ${video.title}")
        btnDownload.isEnabled = false
        btnDownload.text = "DOWNLOADING..."

        // Get downloads directory
        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir == null) {
            Log.e(TAG, "Downloads directory is null")
            showToast("Cannot access downloads directory")
            return
        }

        // Create request
        val request = DownloadManager.Request(Uri.parse(video.url))
            .setTitle(video.title)
            .setDescription("Downloading ${video.title}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setMimeType("video/mp4")
            .addRequestHeader("User-Agent", "Mozilla/5.0")

        // Get DownloadManager
        val dm = getSystemService<DownloadManager>()
        if (dm == null) {
            Log.e(TAG, "DownloadManager is null")
            showToast("DownloadManager not available")
            return
        }

        // Start download and store ID
        val downloadId = dm.enqueue(request)
        downloadIds[video.title] = downloadId
        Log.d(TAG, "✅ Download started for ${video.title} with ID: $downloadId")

        // Re-enable download button after 3 seconds
        btnDownload.postDelayed({
            btnDownload.isEnabled = true
            btnDownload.text = "DOWNLOAD SELECTED"
        }, 3000)
    }

    private fun downloadAllVideos() {
        Log.d(TAG, "Starting download of all ${videoList.size} videos")
        showToast("Downloading all ${videoList.size} videos...")

        btnDownload.isEnabled = false
        binding.btnDownloadAll.isEnabled = false

        // Download each video with delay to avoid overwhelming
        videoList.forEachIndexed { index, video ->
            btnDownload.postDelayed({
                downloadVideo(video)
            }, index * 2000L) // 2 seconds delay between each download
        }

        // Re-enable buttons after all downloads are scheduled
        btnDownload.postDelayed({
            btnDownload.isEnabled = true
            binding.btnDownloadAll.isEnabled = true
            showToast("All downloads started!")
        }, (videoList.size * 2000L) + 3000)
    }

    private fun checkForDownloadedFiles() {
        Log.d(TAG, "=== CHECKING FOR DOWNLOADED FILES ===")
        showToast("Checking for downloaded videos...")

        videoFiles.clear()

        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir == null) {
            Log.e(TAG, "Downloads directory not found")
            showToast("Downloads directory not found")
            return
        }

        val files = downloadsDir.listFiles()
        if (files.isNullOrEmpty()) {
            Log.d(TAG, "No files in downloads directory")
            showToast("No video files found")
            updatePlayButtonState()
            return
        }

        // Find all video files
        Log.d(TAG, "Found ${files.size} files:")
        files.forEach { file ->
            if (file.isFile && file.name.endsWith(".mp4", ignoreCase = true)) {
                Log.d(TAG, "  📁 ${file.name} (${file.length() / 1024} KB)")
                videoFiles.add(file)
            }
        }

        if (videoFiles.isNotEmpty()) {
            Log.d(TAG, "✅ Found ${videoFiles.size} video files")
            showToast("Found ${videoFiles.size} video files")

            // Update spinner with downloaded files
            updateSpinnerWithDownloadedFiles()
        } else {
            Log.d(TAG, "No MP4 files found")
            showToast("No video files found")
        }

        updatePlayButtonState()
    }

    private fun updateSpinnerWithDownloadedFiles() {
        val fileNames = videoFiles.map { it.nameWithoutExtension.replace("_", " ") }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fileNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVideos.adapter = adapter
    }

    private fun updatePlayButtonState() {
        val position = spinnerVideos.selectedItemPosition
        if (position != AdapterView.INVALID_POSITION && position < videoFiles.size) {
            selectedVideoFile = videoFiles[position]
            btnPlay.isEnabled = true
            btnPlay.text = "PLAY: ${videoFiles[position].nameWithoutExtension}"
        } else {
            btnPlay.isEnabled = false
            btnPlay.text = "SELECT A VIDEO"
        }
    }

    private fun playVideo() {
        Log.d(TAG, "=== PLAY VIDEO ===")

        val file = selectedVideoFile
        if (file == null) {
            Log.e(TAG, "No video file selected")
            showToast("Please select a video first")
            return
        }

        Log.d(TAG, "Playing file: ${file.name}")
        Log.d(TAG, "File path: ${file.absolutePath}")
        Log.d(TAG, "File size: ${file.length()} bytes")

        if (!file.exists()) {
            showToast("Video file not found")
            return
        }

        // Create URI for playback
        val videoUri = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating URI: ${e.message}")
            Uri.fromFile(file)
        }

        Log.d(TAG, "Video URI: $videoUri")

        // Initialize player
        if (player == null) {
            initPlayer()
        }

        // Create media item
        val mediaItem = MediaItem.Builder()
            .setUri(videoUri)
            .setMimeType(MimeTypes.VIDEO_MP4)
            .build()

        // Show player
        playerView.visibility = View.VISIBLE
        playerView.useController = true

        // Configure and play
        player?.apply {
            setMediaItem(mediaItem)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.d(TAG, "Player state: $playbackState")
                    when (playbackState) {
                        Player.STATE_READY -> {
                            Log.d(TAG, "✅ Player is READY")
                            showToast("Playing ${file.nameWithoutExtension}...")
                            playWhenReady = true
                        }
                        Player.STATE_BUFFERING -> Log.d(TAG, "🔄 Buffering...")
                        Player.STATE_ENDED -> {
                            Log.d(TAG, "⏹️ Playback ended")
                            showToast("Playback completed")
                        }
                        Player.STATE_IDLE -> Log.d(TAG, "💤 Player idle")
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "❌ Player error: ${error.errorCodeName}")
                    Log.e(TAG, "Error message: ${error.message}")
                    showToast("Play error: ${error.errorCodeName}")
                }
            })

            prepare()
        }
    }

    private fun registerDownloadReceiver() {
        try {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(downloadReceiver, filter)
            }
            Log.d(TAG, "✅ Download receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register receiver: ${e.message}")
        }
    }

    private fun initPlayer() {
        Log.d(TAG, "Initializing ExoPlayer...")
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            Log.d(TAG, "✅ Player initialized")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Toast: $message")
    }
}