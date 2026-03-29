package com.surendramaran.yolov8tflite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.surendramaran.yolov8tflite.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private val reportList = mutableListOf<PotholeReport>()
    private lateinit var adapter: HistoryAdapter
    private val DB_URL = "https://pothhole-detect-default-rtdb.firebaseio.com/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = HistoryAdapter(reportList) { report ->
            val intent = Intent(this, PotholeDetailActivity::class.java).apply {
                putExtra("cost", report.cost)
                putExtra("time", report.time)
                putExtra("status", report.status)
                putExtra("lat", report.lat)
                putExtra("lng", report.lng)
                putExtra("address", report.address)
                putExtra("localImagePath", report.localImagePath)
            }
            startActivity(intent)
        }

        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.historyRecyclerView.adapter = adapter

        fetchHistory()

        binding.newDetectionFab.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        binding.logoutBtn.setOnClickListener {
            logout()
        }
    }

    private fun logout() {
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        sharedPref.edit().putBoolean("isLoggedIn", false).apply()
        
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun fetchHistory() {
        val ref = FirebaseDatabase.getInstance(DB_URL).getReference("potholes")
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                reportList.clear()
                for (data in snapshot.children) {
                    val report = data.getValue(PotholeReport::class.java)
                    report?.let { 
                        it.id = data.key
                        reportList.add(it) 
                    }
                }
                reportList.sortByDescending { it.time }
                adapter.notifyDataSetChanged()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    class HistoryAdapter(
        private val reports: List<PotholeReport>,
        private val onClick: (PotholeReport) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.potholeTitle)
            val date: TextView = view.findViewById(R.id.potholeDate)
            val status: TextView = view.findViewById(R.id.potholeStatus)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pothole_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val report = reports[position]
            holder.title.text = "Pothole Detection: ${report.cost}"
            
            val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            holder.date.text = sdf.format(Date(report.time ?: 0L))
            
            holder.status.text = report.status
            holder.itemView.setOnClickListener { onClick(report) }
        }

        override fun getItemCount() = reports.size
    }
}
