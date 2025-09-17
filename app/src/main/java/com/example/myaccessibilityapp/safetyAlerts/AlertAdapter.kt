package com.example.myaccessibilityapp.safetyAlerts

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myaccessibilityapp.R

class AlertAdapter(
    private val alerts: List<Alert>
) : RecyclerView.Adapter<AlertAdapter.AlertViewHolder>() {

    class AlertViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvAlertTitle)
        val tvMessage: TextView = view.findViewById(R.id.tvAlertMessage)
        val tvSeverity: TextView = view.findViewById(R.id.tvSeverity)
        val tvSuggestionsParagraph: TextView = view.findViewById(R.id.tvSuggestionsParagraph) // now exists
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.alert_item, parent, false)
        return AlertViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        val alert = alerts[position]
        holder.tvTitle.text = alert.title
        holder.tvMessage.text = alert.message
        holder.tvSeverity.text = alert.severity

        // Build paragraph from backend suggestions
        holder.tvSuggestionsParagraph.text = alert.suggestions
    }

    override fun getItemCount(): Int = alerts.size
}
