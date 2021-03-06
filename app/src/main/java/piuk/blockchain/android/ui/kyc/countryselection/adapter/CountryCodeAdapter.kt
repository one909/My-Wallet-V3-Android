package piuk.blockchain.android.ui.kyc.countryselection.adapter

import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.view.ViewGroup
import piuk.blockchain.android.ui.kyc.countryselection.util.CountryDisplayModel
import kotlinx.android.synthetic.main.item_country.view.*
import piuk.blockchain.android.R
import piuk.blockchain.androidcoreui.utils.extensions.autoNotify
import piuk.blockchain.androidcoreui.utils.extensions.inflate
import kotlin.properties.Delegates

class CountryCodeAdapter(
    private val countrySelector: (CountryDisplayModel) -> Unit
) : RecyclerView.Adapter<CountryCodeAdapter.CountryCodeViewHolder>() {

    var items: List<CountryDisplayModel> by Delegates.observable(
        emptyList()
    ) { _, oldList, newList ->
        autoNotify(oldList, newList) { o, n -> o == n }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CountryCodeViewHolder =
        CountryCodeViewHolder(parent.inflate(R.layout.item_country))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: CountryCodeViewHolder, position: Int) {
        holder.bind(items[position], countrySelector)
    }

    class CountryCodeViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {

        private val flag = itemView.text_view_flag
        private val name = itemView.text_view_country_name

        fun bind(
            country: CountryDisplayModel,
            countrySelector: (CountryDisplayModel) -> Unit
        ) {
            flag.text = country.flag
            name.text = country.name

            itemView.setOnClickListener { countrySelector(country) }
        }
    }
}