package com.example.otsled.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.otsled.ui.AppViewModelFactory
import com.example.otsled.ui.products.AddProductScreen
import com.example.otsled.ui.products.ProductDetailScreen
import com.example.otsled.ui.products.ProductListScreen
import com.example.otsled.ui.settings.SettingsScreen

@Composable
fun OtsledNavGraph(
    navController: NavHostController,
    viewModelFactory: AppViewModelFactory,
    startProductId: Long?,
) {
    NavHost(
        navController = navController,
        startDestination = if (startProductId != null) {
            Routes.productDetail(startProductId)
        } else {
            Routes.PRODUCT_LIST
        },
    ) {
        composable(Routes.PRODUCT_LIST) {
            ProductListScreen(
                viewModelFactory = viewModelFactory,
                onAddProduct = { navController.navigate(Routes.ADD_PRODUCT) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenProduct = { id -> navController.navigate(Routes.productDetail(id)) },
            )
        }
        composable(Routes.ADD_PRODUCT) {
            AddProductScreen(
                viewModelFactory = viewModelFactory,
                onBack = { navController.popBackStack() },
                onSaved = {
                    navController.popBackStack()
                },
            )
        }
        composable(
            route = Routes.PRODUCT_DETAIL,
            arguments = listOf(navArgument("productId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val productId = backStackEntry.arguments?.getLong("productId") ?: return@composable
            ProductDetailScreen(
                viewModelFactory = viewModelFactory,
                productId = productId,
                onBack = { navController.popBackStack() },
                onDeleted = {
                    navController.popBackStack(Routes.PRODUCT_LIST, inclusive = false)
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModelFactory = viewModelFactory,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
