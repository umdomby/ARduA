package com.example.ardua

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File

class MusicFilesActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var fileReceiver: BroadcastReceiver
    private lateinit var mp3Files: MutableList<File>
    private var currentPlayingFile: File? = null
    private lateinit var audioManager: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_files)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        listView = findViewById(R.id.musicListView)
        updateFileList()

        // Регистрируем BroadcastReceiver для управления файлами
        fileReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    "com.example.FILE_RECEIVED" -> {
                        val fileName = intent.getStringExtra("fileName")
                        if (fileName != null) {
                            Toast.makeText(context, "Получен MP3: $fileName", Toast.LENGTH_SHORT).show()
                            Log.d("MusicFilesActivity", "Received file: $fileName")
                            updateFileList()
                        }
                    }
                    "com.example.PLAY_FILE" -> {
                        val filePath = intent.getStringExtra("filePath")
                        if (filePath != null) {
                            val file = File(filePath)
                            if (file.exists()) {
                                playMusic(file)
                            } else {
                                Toast.makeText(context, "Файл не найден", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    "com.example.PAUSE_FILE" -> {
                        if (mediaPlayer?.isPlaying == true) {
                            mediaPlayer?.pause()
                            currentPlayingFile = null
                            Toast.makeText(context, "Воспроизведение приостановлено", Toast.LENGTH_SHORT).show()
                            Log.d("MusicFilesActivity", "Paused playback")
                            updateFileList()
                        }
                    }
                    "com.example.FILE_DELETED" -> {
                        val fileName = intent.getStringExtra("fileName")
                        if (fileName != null) {
                            Toast.makeText(context, "Файл удалён: $fileName", Toast.LENGTH_SHORT).show()
                            Log.d("MusicFilesActivity", "Deleted file: $fileName")
                            if (currentPlayingFile?.name == fileName) {
                                mediaPlayer?.release()
                                mediaPlayer = null
                                currentPlayingFile = null
                            }
                            updateFileList()
                        }
                    }
                    "com.example.SET_VOLUME" -> {
                        val volume = intent.getFloatExtra("volume", 1.0f)
                        mediaPlayer?.setVolume(volume, volume)
                        Log.d("MusicFilesActivity", "Set volume to: $volume")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.example.FILE_RECEIVED")
            addAction("com.example.PLAY_FILE")
            addAction("com.example.PAUSE_FILE")
            addAction("com.example.FILE_DELETED")
            addAction("com.example.SET_VOLUME")
        }
        registerReceiver(fileReceiver, filter)
    }

    private fun updateFileList() {
        mp3Files = filesDir.listFiles { file -> file.isFile && file.name.endsWith(".mp3", ignoreCase = true) }?.toMutableList() ?: mutableListOf()
        Log.d("MusicFilesActivity", "Found ${mp3Files.size} MP3 files in ${filesDir.absolutePath}")
        if (mp3Files.isEmpty()) {
            Toast.makeText(this, "Нет MP3 файлов", Toast.LENGTH_SHORT).show()
        }
        val adapter = MusicFilesAdapter(this, mp3Files)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val file = mp3Files[position]
            Log.d("MusicFilesActivity", "Item clicked: ${file.name}")
            playMusic(file)
        }
    }

    private fun playMusic(file: File) {
        try {
            if (currentPlayingFile == file && mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                currentPlayingFile = null
                Toast.makeText(this, "Приостановлено: ${file.name}", Toast.LENGTH_SHORT).show()
                Log.d("MusicFilesActivity", "Paused: ${file.name}")
                updateFileList()
                return
            }

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    Toast.makeText(this@MusicFilesActivity, "Воспроизведение завершено: ${file.name}", Toast.LENGTH_SHORT).show()
                    Log.d("MusicFilesActivity", "Completed: ${file.name}")
                    currentPlayingFile = null
                    updateFileList()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("MusicFilesActivity", "MediaPlayer error: what=$what, extra=$extra")
                    Toast.makeText(this@MusicFilesActivity, "Ошибка воспроизведения: ${file.name}", Toast.LENGTH_LONG).show()
                    currentPlayingFile = null
                    updateFileList()
                    true
                }
            }
            currentPlayingFile = file
            Toast.makeText(this, "Воспроизведение: ${file.name}", Toast.LENGTH_SHORT).show()
            Log.d("MusicFilesActivity", "Playing: ${file.name}")
            updateFileList()
        } catch (e: Exception) {
            Log.e("MusicFilesActivity", "Error playing file ${file.name}: ${e.message}")
            Toast.makeText(this, "Ошибка воспроизведения: ${e.message}", Toast.LENGTH_LONG).show()
            currentPlayingFile = null
            updateFileList()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(fileReceiver)
        mediaPlayer?.release()
        mediaPlayer = null
        Log.d("MusicFilesActivity", "MediaPlayer released")
    }

    private inner class MusicFilesAdapter(context: Context, files: List<File>) :
        ArrayAdapter<File>(context, 0, files) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_music_file, parent, false)
            val file = getItem(position)!!

            val fileNameTextView = view.findViewById<TextView>(R.id.fileNameTextView)
            val playButton = view.findViewById<MaterialButton>(R.id.playButton)

            fileNameTextView.text = file.name
            playButton.setIconResource(
                if (file == currentPlayingFile && mediaPlayer?.isPlaying == true)
                    android.R.drawable.ic_media_pause
                else
                    android.R.drawable.ic_media_play
            )

            playButton.setOnClickListener {
                Log.d("MusicFilesActivity", "Play button clicked for: ${file.name}")
                playMusic(file)
            }

            return view
        }
    }
}