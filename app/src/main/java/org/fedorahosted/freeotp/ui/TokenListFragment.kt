package org.fedorahosted.freeotp.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MenuItemCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.util.TokenCodeUtil
import org.fedorahosted.freeotp.databinding.FragmentTokenListBinding
import org.fedorahosted.freeotp.util.Settings
import org.fedorahosted.freeotp.util.ThemeMode
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
class TokenListFragment : Fragment() {

    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var tokenCodeUtil: TokenCodeUtil
    @Inject lateinit var settings: Settings
    @Inject lateinit var tokenCryptoManager: TokenCryptoManager

    private val viewModel: MainViewModel by activityViewModels()

    private var _binding: FragmentTokenListBinding? = null
    private val binding get() = _binding!!

    private lateinit var tokenListAdapter: TokenListAdapter
    private var categories: List<String> = emptyList()
    private var previousTopTokenId: Long? = null
    private var previousFilterKey: Pair<String, String?>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTokenListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val hostActivity = requireActivity() as AppCompatActivity
        hostActivity.setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.tokenList) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                resources.getDimensionPixelSize(R.dimen.token_list_bottom_padding) + insets.bottom
            )
            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.addTokenFab) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val marginParams = v.layoutParams as ViewGroup.MarginLayoutParams
            marginParams.bottomMargin = resources.getDimensionPixelSize(R.dimen.space_3) + insets.bottom
            if (v.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                marginParams.leftMargin = resources.getDimensionPixelSize(R.dimen.space_3) + insets.left
            } else {
                marginParams.rightMargin = resources.getDimensionPixelSize(R.dimen.space_3) + insets.right
            }
            v.layoutParams = marginParams
            windowInsets
        }

        tokenListAdapter = TokenListAdapter(
            activity = hostActivity,
            lifecycleScope = viewLifecycleOwner.lifecycleScope,
            otpTokenDatabase = otpTokenDatabase,
            tokenCodeUtil = tokenCodeUtil,
            settings = settings,
            tokenCryptoManager = tokenCryptoManager,
            viewModel = viewModel,
            onEdit = { token ->
                navigateFromList(
                    R.id.action_tokenList_to_editToken,
                    EditTokenFragment.args(token.id)
                )
            },
            onDelete = { token ->
                navigateFromList(
                    R.id.action_tokenList_to_deleteToken,
                    DeleteTokenDialogFragment.args(token.id)
                )
            },
            onShareQrCode = { token ->
                navigateFromList(
                    R.id.action_tokenList_to_shareQrCode,
                    ShareQrCodeDialogFragment.args(token.id)
                )
            }
        )

        binding.tokenList.adapter = tokenListAdapter
        val columns = max(1, resources.configuration.screenWidthDp / 320)
        binding.tokenList.layoutManager = GridLayoutManager(requireContext(), columns)

        ItemTouchHelper(TokenTouchCallback(viewLifecycleOwner, tokenListAdapter, otpTokenDatabase))
            .attachToRecyclerView(binding.tokenList)

        setupMenuProvider()

        val existingQuery = viewModel.getCurrentSearchQuery()
        if (existingQuery.isNotEmpty()) {
            binding.searchView.isIconified = false
            binding.searchView.setQuery(existingQuery, false)
        }
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setTokenSearchQuery(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(query: String?): Boolean {
                viewModel.setTokenSearchQuery(query.orEmpty())
                return true
            }
        })

        binding.addTokenFab.setOnClickListener {
            navigateFromList(R.id.action_tokenList_to_scanToken)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.getTokenList().collect { tokens ->
                        val currentFilterKey = viewModel.getCurrentSearchQuery() to viewModel.getCurrentCategoryFilter()
                        val topId = tokens.firstOrNull()?.id
                        val shouldScrollToTop = previousFilterKey == currentFilterKey &&
                            previousTopTokenId != null &&
                            topId != null &&
                            topId != previousTopTokenId
                        previousFilterKey = currentFilterKey
                        previousTopTokenId = topId

                        tokenListAdapter.submitList(tokens) {
                            if (shouldScrollToTop) {
                                _binding?.tokenList?.scrollToPosition(0)
                            }
                        }

                        if (tokens.isEmpty()) {
                            binding.emptyViewContainer.visibility = View.VISIBLE
                            binding.tokenList.visibility = View.GONE
                        } else {
                            binding.emptyViewContainer.visibility = View.GONE
                            binding.tokenList.visibility = View.VISIBLE
                        }
                    }
                }

                launch {
                    viewModel.getAllCategories().collect {
                        categories = it
                        requireActivity().invalidateMenu()
                    }
                }

                launch {
                    viewModel.getCategoryFilter().collect {
                        updateFilterMenuIconTint(binding.toolbar.menu)
                        requireActivity().invalidateMenu()
                    }
                }
            }
        }
    }

    private fun setupMenuProvider() {
        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.main, menu)
                }

                override fun onPrepareMenu(menu: Menu) {
                    val authenticated = viewModel.isAuthenticated()
                    for (i in 0 until menu.size()) {
                        menu.getItem(i).isVisible = authenticated
                    }
                    if (!authenticated) return

                    when (settings.themeMode) {
                        ThemeMode.SYSTEM -> menu.findItem(R.id.theme_system)?.isChecked = true
                        ThemeMode.LIGHT -> menu.findItem(R.id.theme_light)?.isChecked = true
                        ThemeMode.DARK -> menu.findItem(R.id.theme_dark)?.isChecked = true
                    }
                    menu.findItem(R.id.copy_to_clipboard)?.isChecked = settings.copyToClipboard
                    menu.findItem(R.id.quit_and_lock)?.isVisible = settings.authMode != AuthMode.NONE
                    updateFilterMenuIconTint(menu)

                    val filterItem = menu.findItem(R.id.action_filter)
                    val filterMenu = filterItem?.subMenu ?: return
                    filterMenu.clear()

                    val allItem = filterMenu.add(
                        CATEGORY_FILTER_GROUP_ID,
                        R.id.action_filter_all,
                        Menu.NONE,
                        R.string.all_categories
                    )
                    val uncategorizedItem = filterMenu.add(
                        CATEGORY_FILTER_GROUP_ID,
                        R.id.action_filter_uncategorized,
                        Menu.NONE,
                        R.string.uncategorized
                    )

                    val categoryItems = categories.map { category ->
                        category to filterMenu.add(
                            CATEGORY_FILTER_GROUP_ID,
                            Menu.NONE,
                            Menu.NONE,
                            category
                        ).apply {
                            setOnMenuItemClickListener {
                                viewModel.setCategoryFilter(category)
                                true
                            }
                        }
                    }

                    filterMenu.setGroupCheckable(CATEGORY_FILTER_GROUP_ID, true, true)
                    when (val currentFilter = viewModel.getCurrentCategoryFilter()) {
                        null -> allItem.isChecked = true
                        MainViewModel.CATEGORY_UNCATEGORIZED -> uncategorizedItem.isChecked = true
                        else -> categoryItems.firstOrNull { it.first == currentFilter }?.second?.isChecked = true
                    }
                }

                override fun onMenuItemSelected(item: MenuItem): Boolean {
                    if (!viewModel.isAuthenticated()) return false
                    val mainActivity = requireActivity() as MainActivity
                    return when (item.itemId) {
                        R.id.action_scan -> {
                            navigateFromList(R.id.action_tokenList_to_scanToken)
                            true
                        }

                        R.id.action_add -> {
                            navigateFromList(R.id.action_tokenList_to_addToken)
                            true
                        }

                        R.id.action_filter_all -> {
                            viewModel.setCategoryFilter(null)
                            true
                        }

                        R.id.action_filter_uncategorized -> {
                            viewModel.setCategoryFilter(MainViewModel.CATEGORY_UNCATEGORIZED)
                            true
                        }

                        R.id.action_import_json -> {
                            mainActivity.startImportJson()
                            true
                        }

                        R.id.action_import_key_uri -> {
                            mainActivity.startImportKeyUri()
                            true
                        }

                        R.id.action_export_json -> {
                            mainActivity.handleExportAction(isJson = true)
                            true
                        }

                        R.id.action_export_key_uri -> {
                            mainActivity.handleExportAction(isJson = false)
                            true
                        }

                        R.id.theme_system -> {
                            applyThemeMode(ThemeMode.SYSTEM, item, mainActivity)
                            true
                        }

                        R.id.theme_light -> {
                            applyThemeMode(ThemeMode.LIGHT, item, mainActivity)
                            true
                        }

                        R.id.theme_dark -> {
                            applyThemeMode(ThemeMode.DARK, item, mainActivity)
                            true
                        }

                        R.id.copy_to_clipboard -> {
                            settings.copyToClipboard = !settings.copyToClipboard
                            item.isChecked = settings.copyToClipboard
                            mainActivity.invalidateMenu()
                            true
                        }

                        R.id.authentication_mode -> {
                            mainActivity.showAuthenticationModeDialog()
                            true
                        }

                        R.id.action_about -> {
                            navigateFromList(R.id.action_tokenList_to_about)
                            true
                        }

                        R.id.quit_and_lock -> {
                            viewModel.lockSession()
                            mainActivity.finish()
                            true
                        }

                        else -> false
                    }
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )
    }

    private fun applyThemeMode(mode: ThemeMode, item: MenuItem, mainActivity: MainActivity) {
        settings.themeMode = mode
        item.isChecked = true
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
        if (!mainActivity.isChangingConfigurations && !mainActivity.isFinishing && !mainActivity.isDestroyed) {
            mainActivity.invalidateMenu()
        }
    }

    private fun navigateFromList(actionId: Int, args: Bundle? = null) {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.tokenListFragment) {
            navController.navigate(actionId, args)
        }
    }

    private fun updateFilterMenuIconTint(menu: Menu?) {
        val currentBinding = _binding ?: return
        val filterItem = menu?.findItem(R.id.action_filter) ?: return
        val isFilterActive = viewModel.getCurrentCategoryFilter() != null
        val tintAttr = if (isFilterActive) {
            androidx.appcompat.R.attr.colorPrimary
        } else {
            androidx.appcompat.R.attr.colorControlNormal
        }
        val tintColor = MaterialColors.getColor(currentBinding.toolbar, tintAttr)
        val tintList = ColorStateList.valueOf(tintColor)
        MenuItemCompat.setIconTintList(filterItem, tintList)
        filterItem.icon?.mutate()?.let { icon ->
            DrawableCompat.setTintList(icon, tintList)
            filterItem.icon = icon
        }
    }

    override fun onDestroyView() {
        binding.tokenList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val CATEGORY_FILTER_GROUP_ID = 100
    }
}
