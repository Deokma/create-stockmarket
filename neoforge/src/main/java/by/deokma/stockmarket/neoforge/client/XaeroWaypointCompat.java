package by.deokma.stockmarket.neoforge.client;

import by.deokma.stockmarket.shop.ShopEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * Совместимость с Xaero's Minimap для открытия окна добавления вайпоинта.
 * <p>
 * Нужный deprecated-конструктор GuiAddWaypoint (из декомпиляции):
 * <p>
 * GuiAddWaypoint(
 * IXaeroMinimap  modMain,
 * WaypointsManager manager,
 * Screen         par1GuiScreen,
 * Screen         escapeScreen,
 * Waypoint       point,           <- null = новый вайпоинт
 * String         defaultParentContainer,
 * WaypointWorld  defaultWorld,
 * String         waypointSet,     <- String ID набора, не объект WaypointSet!
 * boolean        hasForcedPlayerPos,
 * int            forcedPlayerX,
 * int            forcedPlayerY,
 * int            forcedPlayerZ,
 * double         forcedPlayerScale,
 * WaypointWorld  forcedCoordSrcWorld
 * )
 * <p>
 * Цепочка получения данных (из SupportXaeroMinimap.java):
 * modMain       = XaeroMinimapCore.modMain
 * session       = XaeroMinimapSession.getCurrentSession()
 * manager       = session.getWaypointsManager()
 * waypointWorld = manager.getCurrentWorld()
 * rootKey       = waypointWorld.getContainer().getRootContainer().getKey()
 * setId         = waypointWorld.getCurrentWaypointSetId()   (String)
 */
public final class XaeroWaypointCompat {

    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

    private static final String MOD_MINIMAP = "xaerominimap";

    private static final String CLS_SESSION = "xaero.common.XaeroMinimapSession";
    private static final String CLS_CORE = "xaero.common.core.XaeroMinimapCore";
    private static final String CLS_WAYPOINT_WORLD = "xaero.common.minimap.waypoints.WaypointWorld";
    private static final String CLS_GUI_ADD_WAYPOINT = "xaero.common.gui.GuiAddWaypoint";

    private XaeroWaypointCompat() {
    }

    // -------------------------------------------------------------------------
    // Публичный API
    // -------------------------------------------------------------------------

    public static boolean isPresent() {
        // GuiAddWaypoint и вся система вайпоинтов живёт в Minimap.
        // WorldMap без Minimap — окна добавления вайпоинта нет совсем.
        return ModList.get().isLoaded(MOD_MINIMAP);
    }

    public static boolean addShopWaypoint(ShopEntry entry) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;

        if (!isPresent()) {
            mc.player.displayClientMessage(
                    Component.translatable("screen.stockmarket.xaero_not_installed"), true);
            return false;
        }

        boolean opened = openGuiAddWaypoint(entry);
        if (!opened) {
            mc.player.displayClientMessage(
                    Component.translatable("screen.stockmarket.xaero_open_failed"), true);
        }
        return opened;
    }

    // -------------------------------------------------------------------------
    // Основная логика
    // -------------------------------------------------------------------------

    private static boolean openGuiAddWaypoint(ShopEntry entry) {
        // Minimap 24+ moved sessions/worlds to xaero.hud.*. The old XaeroMinimapSession path
        // below still exists there but its constructor lookup matched two overloads and
        // failed at random, which is the "Couldn't open the Xaero waypoint menu" report.
        Exception modernFailure;
        try {
            if (openModern(entry)) return true;
            modernFailure = null;
        } catch (Exception e) {
            modernFailure = e;
        }
        try {
            if (openLegacy(entry)) return true;
            logFailure(modernFailure);
        } catch (Exception e) {
            if (modernFailure != null) e.addSuppressed(modernFailure);
            logFailure(e);
        }
        return false;
    }

    private static boolean failureLogged = false;

    private static void logFailure(Exception e) {
        if (failureLogged) return;
        failureLogged = true;
        LOGGER.warn("[XaeroWaypointCompat] Could not open Xaero's add-waypoint screen — "
                + "this Xaero's Minimap version is probably not supported yet.", e);
    }

    /**
     * Minimap 24+ — the same call Xaero's own world map makes:
     * {@code new GuiAddWaypoint(HudMod, MinimapSession, parent, escape, waypoints, rootPath,
     * world, setId, adding, hasForcedPlayerPos, x, y, z, coordinateScale, coordSourceWorld)}.
     */
    private static boolean openModern(ShopEntry entry) throws Exception {
        Class<?> clsHudMod;
        try {
            clsHudMod = Class.forName("xaero.common.HudMod");
        } catch (ClassNotFoundException e) {
            return false; // pre-24 Minimap — handled by openLegacy
        }
        Class<?> clsSession = Class.forName("xaero.hud.minimap.module.MinimapSession");
        Class<?> clsWorld   = Class.forName("xaero.hud.minimap.world.MinimapWorld");
        Class<?> clsPath    = Class.forName("xaero.hud.path.XaeroPath");

        Object modMain = clsHudMod.getField("INSTANCE").get(null);
        Object module  = Class.forName("xaero.hud.minimap.BuiltInHudModules").getField("MINIMAP").get(null);
        Object session = module.getClass().getMethod("getCurrentSession").invoke(module);
        if (modMain == null || session == null) return false;

        Object worldState = clsSession.getMethod("getWorldState").invoke(session);
        Object worldPath  = worldState.getClass().getMethod("getCurrentWorldPath").invoke(worldState);
        Object rootPath   = clsPath.getMethod("getRoot").invoke(worldPath);

        Object worldManager = clsSession.getMethod("getWorldManager").invoke(session);
        Object world = worldManager.getClass().getMethod("getCurrentWorld").invoke(worldManager);
        if (world == null) return false;
        String setId = (String) clsWorld.getMethod("getCurrentWaypointSetId").invoke(world);

        Constructor<?> ctor = Class.forName(CLS_GUI_ADD_WAYPOINT).getConstructor(
                clsHudMod, clsSession, Screen.class, Screen.class, ArrayList.class, clsPath,
                clsWorld, String.class, boolean.class, boolean.class,
                int.class, int.class, int.class, double.class, clsWorld);

        Minecraft mc = Minecraft.getInstance();
        Screen parent = mc.screen;
        Screen screen = (Screen) ctor.newInstance(
                modMain, session, parent, parent,
                new ArrayList<>(),   // nothing being edited — Xaero creates the new waypoint
                rootPath, world, setId,
                true,                // adding
                true,                // hasForcedPlayerPos — use the shop's position
                entry.pos().getX(), entry.pos().getY(), entry.pos().getZ(),
                dimensionScale(entry.dimensionId()),
                world);
        mc.setScreen(screen);
        tryPresetWaypointName(screen, buildWaypointName(entry));
        return true;
    }

    /** Minimap before 24: the deprecated 14-argument constructor, matched by exact signature. */
    private static boolean openLegacy(ShopEntry entry) {
        try {
            // 1. modMain = XaeroMinimapCore.modMain
            Field fModMain = Class.forName(CLS_CORE).getField("modMain");
            Object modMain = fModMain.get(null);
            if (modMain == null) return false;

            // 2. session = XaeroMinimapSession.getCurrentSession()
            Class<?> clsSession = Class.forName(CLS_SESSION);
            Object session = clsSession.getMethod("getCurrentSession").invoke(null);
            if (session == null) return false;

            // 3. manager = session.getWaypointsManager()
            Object manager = session.getClass().getMethod("getWaypointsManager").invoke(session);
            if (manager == null) return false;

            // 4. waypointWorld = manager.getCurrentWorld()
            Object waypointWorld = manager.getClass().getMethod("getCurrentWorld").invoke(manager);
            if (waypointWorld == null) return false;

            // 5. rootKey = waypointWorld.getContainer().getRootContainer().getKey()
            Object container = waypointWorld.getClass().getMethod("getContainer").invoke(waypointWorld);
            Object rootContainer = container.getClass().getMethod("getRootContainer").invoke(container);
            String rootKey = (String) rootContainer.getClass().getMethod("getKey").invoke(rootContainer);

            // 6. setId — строковый ID текущего набора вайпоинтов.
            //    Конструктор принимает String, не WaypointSet!
            //    Пробуем несколько вариантов имени метода (API менялся между версиями).
            String setId = resolveCurrentSetId(waypointWorld);
            if (setId == null) return false;

            // 7. Конструктор берём по точной сигнатуре. Поиск «по признакам» совпадал и с
            //    15-параметровой перегрузкой, и вызов с 14 аргументами падал.
            Class<?> clsWorld = Class.forName(CLS_WAYPOINT_WORLD);
            Class<?> clsGui = Class.forName(CLS_GUI_ADD_WAYPOINT);
            Constructor<?> ctor = clsGui.getConstructor(
                    Class.forName("xaero.common.IXaeroMinimap"),
                    Class.forName("xaero.common.minimap.waypoints.WaypointsManager"),
                    Screen.class, Screen.class,
                    Class.forName("xaero.common.minimap.waypoints.Waypoint"),
                    String.class, clsWorld, String.class, boolean.class,
                    int.class, int.class, int.class, double.class, clsWorld);

            Minecraft mc = Minecraft.getInstance();
            Screen parent = mc.screen;
            int x = entry.pos().getX();
            int y = entry.pos().getY();
            int z = entry.pos().getZ();
            double scale = dimensionScale(entry.dimensionId());

            // Аргументы соответствуют deprecated-конструктору из GuiAddWaypoint.java:
            // (modMain, manager, screen, screen, null, rootKey, world, setId,
            //  hasForcedPlayerPos=true, x, y, z, scale, coordSrcWorld=waypointWorld)
            Object screen = ctor.newInstance(
                    modMain, manager,
                    parent, parent,
                    null,             // Waypoint point = null → новый вайпоинт
                    rootKey,          // String defaultParentContainer
                    waypointWorld,    // WaypointWorld defaultWorld
                    setId,            // String waypointSet  (не WaypointSet!)
                    true,             // boolean hasForcedPlayerPos
                    x, y, z,
                    scale,            // double forcedPlayerScale
                    waypointWorld     // WaypointWorld forcedCoordSrcWorld
            );

            mc.setScreen((Screen) screen);
            // init() вызывается синхронно внутри setScreen() — к этому моменту
            // EditBox для имени уже создан, можно прописать название магазина.
            tryPresetWaypointName((Screen) screen, buildWaypointName(entry));
            return true;

        } catch (ReflectiveOperationException e) {
            // Нет такого API — значит, это не pre-24 Minimap.
            return false;
        }
    }

    /**
     * Формирует имя вайпоинта из данных магазина.
     * Формат: «OwnerName» (имя владельца магазина).
     */
    private static String buildWaypointName(ShopEntry entry) {
        return entry.ownerName();
    }

    /**
     * Находит первый EditBox в иерархии полей экрана и устанавливает в него имя.
     * <p>
     * Должен вызываться ПОСЛЕ mc.setScreen(), т.к. init() создаёт виджеты
     * только в момент установки экрана.
     * <p>
     * Стратегия поиска:
     * 1. Сначала проверяем характерные имена полей из исходников Xaero.
     * 2. Если не нашли — ищем первое поле типа EditBox в классе и его предках.
     */
    private static void tryPresetWaypointName(Screen screen, String name) {
        if (name == null || name.isBlank()) return;

        // Известные имена поля для имени вайпоинта в разных версиях Xaero
        String[] knownFieldNames = {"nameTextField", "tf_name", "nameField", "nameEditBox", "textName", "nameBox", "waypointNameField"};

        for (Class<?> cls = screen.getClass(); cls != null; cls = cls.getSuperclass()) {
            // Попытка 1: по известным именам полей
            for (String fieldName : knownFieldNames) {
                try {
                    Field f = cls.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object widget = f.get(screen);
                    if (widget != null) {
                        widget.getClass().getMethod("setValue", String.class).invoke(widget, name);
                        return;
                    }
                } catch (Exception ignored) {
                }
            }

            // Попытка 2: первый EditBox среди всех полей класса
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(screen);
                    if (val instanceof EditBox eb) {
                        eb.setValue(name);
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Получает строковый ID текущего набора вайпоинтов из WaypointWorld.
     * Пробуем несколько вариантов: API Xaero менялся между версиями.
     */
    private static String resolveCurrentSetId(Object waypointWorld) {
        String[] candidates = {
                "getCurrentWaypointSetId",  // вероятный актуальный метод
                "getCurrentSetId",
                "getCurrent",               // может вернуть String в старых версиях
                "getSelectedSetName",
        };
        for (String name : candidates) {
            try {
                Object result = waypointWorld.getClass().getMethod(name).invoke(waypointWorld);
                if (result instanceof String s && !s.isEmpty()) return s;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Вспомогательные методы
    // -------------------------------------------------------------------------

    private static double dimensionScale(String dimensionId) {
        ResourceLocation id = ResourceLocation.tryParse(dimensionId);
        if (id != null && id.equals(Level.NETHER.location())) return 8.0;
        return 1.0;
    }
}