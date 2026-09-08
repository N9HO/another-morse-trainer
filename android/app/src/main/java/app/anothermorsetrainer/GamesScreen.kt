package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Castle
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Games sub-menu (#207): the six arcade games on their own grid, one tap
 * from the home screen's Games tile. The home grid had outgrown two screens
 * of tiles once the games landed (#170), and a beginner scrolling past six
 * arcade modes to find Common Words is not what the grid is for. Same tiles
 * as the home screen ([HomeItem] / [ModeTile]), same order the games had
 * there; Back from a game returns here, and Back from here returns home.
 */
@Composable
fun GamesScreen(
    onBack: () -> Unit,
    onPickInvaders: () -> Unit,
    onPickGalaga: () -> Unit,
    onPickDefender: () -> Unit,
    onPickDungeon: () -> Unit,
    onPickFrogger: () -> Unit,
    onPickAsteroids: () -> Unit
) {
    BackHandler { onBack() }

    val items = listOf(
        HomeItem(stringResource(R.string.mode_invaders), stringResource(R.string.home_arcade_recognition), Icons.Filled.SportsEsports, onPickInvaders),
        HomeItem(stringResource(R.string.mode_galaga), stringResource(R.string.home_arcade_formations), Icons.Filled.Flight, onPickGalaga),
        HomeItem(stringResource(R.string.mode_defender), stringResource(R.string.home_arcade_callsign_copy), Icons.Filled.Shield, onPickDefender),
        HomeItem(stringResource(R.string.mode_dungeon), stringResource(R.string.home_roguelike_sending), Icons.Filled.Castle, onPickDungeon),
        HomeItem(stringResource(R.string.mode_frogger), stringResource(R.string.home_arcade_crossing), Icons.Filled.Pets, onPickFrogger),
        HomeItem(stringResource(R.string.mode_asteroids), stringResource(R.string.home_arcade_sending), Icons.Filled.RocketLaunch, onPickAsteroids)
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(
                stringResource(R.string.mode_games),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Brand.textPrimary
            )
        }

        CenteredScrollColumn(
            contentModifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.games_lead),
                fontSize = 13.sp,
                color = Brand.textSecondary
            )
            Spacer(Modifier.height(16.dp))

            // Two-column tile grid, as on the home screen.
            items.chunked(2).forEach { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEach { item -> ModeTile(item, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}
