package com.simach.arepo

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.mruk.AnchorProceduralMesh
import com.meta.spatial.mruk.AnchorProceduralMeshConfig
import com.meta.spatial.mruk.MRUKAnchorTexCoordMode
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKLabel
import com.meta.spatial.mruk.MRUKLoadDeviceResult
import com.meta.spatial.mruk.MRUKSpawnMode
import com.meta.spatial.mruk.MRUKWallTexCoordModeU
import com.meta.spatial.mruk.MRUKWallTexCoordModeV
import com.meta.spatial.physics.PhysicsFeature
import com.meta.spatial.physics.PhysicsWorldBounds
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature

class AREPOActivity : AppSystemActivity() {
    lateinit var mruk: MRUKFeature
    private lateinit var physics: PhysicsFeature
    private var occluders: AnchorProceduralMesh? = null
    lateinit var game: GameSystem

    override fun registerFeatures(): List<SpatialFeature> {
        mruk = MRUKFeature(this, systemManager)
        physics = PhysicsFeature(spatial, worldBounds = PhysicsWorldBounds(minY = -100.0f))
        return listOf(physics, VRFeature(this), ComposeFeature(), mruk)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        game = GameSystem(this)
        systemManager.registerSystem(game)
        systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
        scene.enablePassthrough(true)
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setLightingEnvironment(
            ambientColor = Vector3(0.35f),
            sunColor = Vector3(4.0f, 3.8f, 3.6f),
            sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
            environmentIntensity = 0.4f,
        )

        // Невидимые стены, пол и мебель: дают физику и прячут виртуальное за реальными стенами
        val mat = try { AnchorProceduralMeshConfig(SceneMaterial.depthOnly(false), true) } catch (e: Throwable) { null }
        val cfg = mat ?: AnchorProceduralMeshConfig(null, true)
        val labels = listOf(
            MRUKLabel.FLOOR, MRUKLabel.WALL_FACE, MRUKLabel.INVISIBLE_WALL_FACE, MRUKLabel.CEILING, MRUKLabel.TABLE, MRUKLabel.COUCH,
            MRUKLabel.STORAGE, MRUKLabel.BED, MRUKLabel.SCREEN, MRUKLabel.LAMP, MRUKLabel.PLANT, MRUKLabel.OTHER
        )
        try {
            occluders = AnchorProceduralMesh(
                mruk, labels.associateWith { cfg },
                MRUKSpawnMode.ALL_ROOMS, MRUKWallTexCoordModeU.METRIC, MRUKWallTexCoordModeV.METRIC, MRUKAnchorTexCoordMode.METRIC,
                listOf(MRUKLabel.DOOR_FRAME, MRUKLabel.WINDOW_FRAME)
            )
        } catch (e: Throwable) {
            Ui.toast("Окклюзия недоступна: ${e.javaClass.simpleName}", 1)
        }

        // панели
        game.menuPanel = Entity.createPanelEntity(R.id.menu_panel, Transform(Pose(Vector3(0f, 1.3f, 1.2f))))
        game.wristPanel = Entity.createPanelEntity(R.id.wrist_panel, Transform(Pose(Vector3(0f, -10f, 0f))))
        game.wristPanel?.setComponent(Visible(false))

        if (checkSelfPermission(PERMISSION_USE_SCENE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(PERMISSION_USE_SCENE), REQ_SCENE)
        } else loadScene()
        game.setPhase(Phase.LOADING)
    }

    fun loadScene() {
        Ui.status = "Загружаю отсканированные комнаты…"
        try {
            mruk.loadSceneFromDevice().whenComplete { result: MRUKLoadDeviceResult?, err: Throwable? ->
                runOnUiThread {
                    val ok = err == null && result == MRUKLoadDeviceResult.SUCCESS
                    val msg = when (result) {
                        MRUKLoadDeviceResult.ERROR_NO_ROOMS_FOUND -> "На шлеме ещё нет отсканированных комнат."
                        MRUKLoadDeviceResult.ERROR_PERMISSION_INSUFFICIENT -> "Игре не дали доступ к данным пространства."
                        null -> "Ошибка загрузки: ${err?.message ?: ""}"
                        else -> "Не удалось загрузить комнаты: $result"
                    }
                    game.onSceneLoaded(ok, msg)
                }
            }
        } catch (e: Throwable) {
            game.onSceneLoaded(false, "Ошибка: ${e.javaClass.simpleName}")
        }
    }

    fun requestCapture() {
        runOnUiThread {
            Ui.status = "Открываю настройку пространства…"
            try {
                scene.requestSceneCapture().whenComplete { _, _ -> runOnUiThread { loadScene() } }
            } catch (e: Throwable) {
                Ui.status = "Открой вручную: Настройки → Физическое пространство → Настройка пространства"
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SCENE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) loadScene()
            else game.onSceneLoaded(false, "Без доступа к пространству игра не видит твою квартиру. Разреши доступ и перезапусти игру.")
        }
    }

    override fun onSpatialShutdown() {
        try { occluders?.destroy() } catch (e: Throwable) { }
        super.onSpatialShutdown()
    }

    override fun registerPanels(): List<PanelRegistration> = listOf(
        ComposeViewPanelRegistration(
            R.id.menu_panel,
            composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { MenuPanel() } } },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = 0.9f, height = 0.75f),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(),
                )
            },
        ),
        ComposeViewPanelRegistration(
            R.id.wrist_panel,
            composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { WristPanel() } } },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = 0.26f, height = 0.22f),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(),
                )
            },
        ),
    )

    companion object {
        const val PERMISSION_USE_SCENE = "com.oculus.permission.USE_SCENE"
        const val REQ_SCENE = 1
    }
}
