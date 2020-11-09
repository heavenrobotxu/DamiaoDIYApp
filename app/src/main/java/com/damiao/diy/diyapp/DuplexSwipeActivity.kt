package com.damiao.diy.diyapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.damiao.diy.view.swipe.DuplexSwipeRefreshLayout
import kotlinx.android.synthetic.main.activity_duplex_swipe.*
import kotlinx.android.synthetic.main.item_layout_duplex_swipe_demo_bg.view.*

class DuplexSwipeActivity : AppCompatActivity() {

    private val items = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_duplex_swipe)

        /*设置顶部刷新球刷新回调，将SwipeRefreshLayout原有的setOnRefreshListener单独分为两个，
        分别表示顶部和底部两个指示球的刷新中回调*/
        srl_duplex_swipe_bg.setOnTopRefreshListener {
            srl_duplex_swipe_bg.postDelayed({
                initItem()
                //使顶部刷新球消失
                srl_duplex_swipe_bg.setTopRefreshing(false)
                rv_duplex_swipe_info.adapter?.notifyDataSetChanged()
            }, 3000)
        }
        //设置底部刷新球回调
        srl_duplex_swipe_bg.setOnBottomRefreshListener {
            srl_duplex_swipe_bg.postDelayed({
                val oldSize = items.size
                for (i in 1..10) {
                    items.add(i)
                }
                rv_duplex_swipe_info.adapter?.notifyItemRangeInserted(items.size - 1, 10)
                rv_duplex_swipe_info.scrollToPosition(oldSize)
                //使底部刷新球消失
                srl_duplex_swipe_bg.setBottomRefreshing(false)
            }, 3000)
        }
        initItem()
        rv_duplex_swipe_info.adapter = PikachuAdapter()
    }

    private fun initItem() {
        items.clear()
        for (i in 1..10) {
            items.add(i)
        }
    }

    class PikachuViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class PikachuAdapter : RecyclerView.Adapter<PikachuViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PikachuViewHolder {
            return PikachuViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_layout_duplex_swipe_demo_bg, parent, false)
            )
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: PikachuViewHolder, position: Int) {
            holder.itemView.iv_item_game.setOnClickListener {
                if (position == 0) {
                    srl_duplex_swipe_bg.setTopRefreshing(true)
                }
                if (position == 1) {
                    srl_duplex_swipe_bg.setBottomRefreshing(true)
                }
            }
        }

    }
}