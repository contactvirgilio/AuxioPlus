package org.oxycblt.auxio.radio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.session.MediaController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.oxycblt.auxio.radio.databinding.ActivityMainBinding
import org.oxycblt.auxio.radio.visualizer.VisualizerActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var player: PlayerConnection
    private lateinit var favoritesAdapter: StationAdapter
    private lateinit var searchAdapter: StationAdapter
    private var searchJob: Job? = null
    private var lastQuery: String? = null
    private var searchError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Favorites.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.bottomNav.updatePadding(bottom = bars.bottom)
            // With the keyboard up, the bottom bar hides behind it and the list ends at its top.
            binding.bottomNav.isVisible = ime.bottom == 0
            v.updatePadding(bottom = if (ime.bottom > 0) ime.bottom else 0)
            insets
        }

        player = PlayerConnection(this, ::onPlayerChanged)

        favoritesAdapter =
            StationAdapter(
                favoritesList = true,
                onClick = { _, i -> play(Favorites.stations.value, i) },
                onButton = ::showFavoriteMenu,
            )
        searchAdapter =
            StationAdapter(
                favoritesList = false,
                onClick = { station, _ -> play(listOf(station), 0) },
                // The favourites listener below refreshes the hearts.
                onButton = { station, _ -> Favorites.toggle(station) },
            )
        binding.favoritesList.adapter = favoritesAdapter
        binding.searchList.adapter = searchAdapter
        reorderHelper.attachToRecyclerView(binding.favoritesList)

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_add_url) StationDialog.show(this, null)
            true
        }

        binding.searchInput.doAfterTextChanged { scheduleSearch(it?.toString().orEmpty(), DEBOUNCE_MS) }
        binding.searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                scheduleSearch(binding.searchInput.text.toString(), 0, force = true)
                hideKeyboard()
                true
            } else false
        }

        binding.bottomNav.setOnItemSelectedListener {
            showTab(it.itemId)
            true
        }
        binding.bottomNav.setOnItemReselectedListener {
            if (it.itemId == R.id.tab_search) {
                binding.searchInput.requestFocus()
                getSystemService(InputMethodManager::class.java)
                    .showSoftInput(binding.searchInput, 0)
            }
        }
        showTab(binding.bottomNav.selectedItemId)

        binding.miniPlayer.setOnClickListener {
            startActivity(Intent(this, VisualizerActivity::class.java))
        }
        binding.miniPlay.setOnClickListener { player.togglePlaying() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Favorites.stations.collect { list ->
                    favoritesAdapter.submitList(list)
                    binding.favoritesEmpty.isVisible = list.isEmpty() && isFavoritesTab()
                    // Hearts in the search results follow along.
                    searchAdapter.notifyItemRangeChanged(0, searchAdapter.itemCount)
                }
            }
        }
    }

    private fun isFavoritesTab() = binding.bottomNav.selectedItemId == R.id.tab_favorites

    private fun showTab(id: Int) {
        val favorites = id == R.id.tab_favorites
        binding.toolbar.title = getString(if (favorites) R.string.lbl_favorites else R.string.lbl_search)
        binding.toolbar.menu.findItem(R.id.action_add_url).isVisible = favorites
        binding.favoritesList.isVisible = favorites
        binding.favoritesEmpty.isVisible = favorites && Favorites.stations.value.isEmpty()
        binding.searchBar.isVisible = !favorites
        binding.searchList.isVisible = !favorites
        binding.searchEmpty.isVisible = !favorites && searchError
        if (favorites) {
            hideKeyboard()
        } else if (lastQuery == null) {
            scheduleSearch("", 0)
        }
    }

    private fun scheduleSearch(query: String, delayMs: Long, force: Boolean = false) {
        if (!force && query.trim() == lastQuery) return
        lastQuery = query.trim()
        searchJob?.cancel()
        searchJob =
            lifecycleScope.launch {
                delay(delayMs)
                binding.searchProgress.isVisible = true
                try {
                    val results = RadioBrowser.search(query)
                    searchError = false
                    searchAdapter.submitList(results) { binding.searchList.scrollToPosition(0) }
                    binding.searchEmpty.setText(R.string.lng_no_results)
                    binding.searchEmpty.isVisible = results.isEmpty() && !isFavoritesTab()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    searchError = true
                    lastQuery = null // Allow the same search again.
                    binding.searchEmpty.setText(R.string.lng_search_failed)
                    binding.searchEmpty.isVisible = !isFavoritesTab()
                } finally {
                    binding.searchProgress.isVisible = false
                }
            }
    }

    private fun hideKeyboard() {
        binding.searchInput.clearFocus()
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun play(stations: List<Station>, index: Int) {
        askForNotifications()
        player.play(stations, index)
    }

    private fun askForNotifications() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun showFavoriteMenu(station: Station, anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.favorite_actions, menu)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_edit -> StationDialog.show(this@MainActivity, station)
                    R.id.action_remove -> Favorites.remove(station)
                }
                true
            }
            show()
        }
    }

    private fun onPlayerChanged(c: MediaController) {
        val item = c.currentMediaItem
        binding.miniPlayer.isVisible = item != null
        val id = item?.mediaId
        favoritesAdapter.playingId = id
        searchAdapter.playingId = id
        val station = player.currentStation() ?: return
        binding.miniName.text = station.name
        binding.miniStatus.text = c.statusText(this)
        binding.miniStatus.isSelected = true // Lets long song titles scroll.
        Logos.load(binding.miniLogo, binding.miniLetter, station)
        val playing = c.playWhenReady && c.playerError == null
        binding.miniPlay.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.miniPlay.contentDescription =
            getString(if (playing) R.string.desc_pause else R.string.desc_play)
    }

    /** Long press a favourite and drag it to change the order. */
    private val reorderHelper =
        ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    Favorites.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            }
        )

    private companion object {
        const val DEBOUNCE_MS = 450L
    }
}
