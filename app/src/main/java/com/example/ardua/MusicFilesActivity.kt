package com.example.ardua

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_files)

        listView = findViewById(R.id.musicListView)
        updateFileList()

        // Регистрируем BroadcastReceiver для получения новых файлов
        fileReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val fileName = intent.getStringExtra("fileName")
                if (fileName != null) {
                    Toast.makeText(context, "Получен MP3: $fileName", Toast.LENGTH_SHORT).show()
                    updateFileList()
                }
            }
        }
        val filter = IntentFilter("com.example.FILE_RECEIVED")
        registerReceiver(fileReceiver, filter)
    }

    private fun updateFileList() {
        mp3Files = filesDir.listFiles { file -> file.isFile && file.name.endsWith(".mp3", ignoreCase = true) }?.toMutableList() ?: mutableListOf()
        val adapter = MusicFilesAdapter(this, mp3Files)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            playMusic(mp3Files[position])
        }
    }

    private fun playMusic(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    Toast.makeText(this@MusicFilesActivity, "Воспроизведение завершено: ${file.name}", Toast.LENGTH_SHORT).show()
                }
            }
            Toast.makeText(this, "Воспроизведение: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка воспроизведения: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(fileReceiver)
        mediaPlayer?.release()
    }

    private class MusicFilesAdapter(context: Context, files: List<File>) :
        ArrayAdapter<File>(context, 0, files) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_music_file, parent, false)
            val file = getItem(position)!!

            val fileNameTextView = view.findViewById<TextView>(R.id.fileNameTextView)
            val playButton = view.findViewById<MaterialButton>(R.id.playButton)

            fileNameTextView.text = file.name
            playButton.setOnClickListener {
                (context as MusicFilesActivity).playMusic(file)
            }

            return view
        }
    }
}