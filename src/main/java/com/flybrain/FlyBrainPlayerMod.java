package com.flybrain;

import com.flybrain.brain.BrainPlayerEntity;
import com.flybrain.block.ProfileSkullBlock;
import com.flybrain.block.RedstoneBrainBlock;
import com.flybrain.block.CarvedPumpkinHeadBlock;
import com.flybrain.learn.KnowledgeBase;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.SubscribeEvent;
import org.slf4j.Logger;

/**
 * Fly Brain Player — 果蝇脑 AI 玩家
 * 用红石块(果蝇脑) + 雕刻南瓜(扫描仪) 组装 AI 玩家实体。
 */
@Mod(FlyBrainPlayerMod.MOD_ID)
public class FlyBrainPlayerMod {
    public static final String MOD_ID = "flybrainplayer";
    public static final Logger LOGGER = LogUtils.getLogger();

    // ============ 方块 ============
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);

    /** 果蝇大脑（红石块）：AI 玩家核心组件 */
    public static final DeferredHolder<Block, RedstoneBrainBlock> FLY_BRAIN_BLOCK =
            BLOCKS.registerBlock("fly_brain_block",
                    props -> new RedstoneBrainBlock(props.mapColor(MapColor.COLOR_RED).strength(1.5f)),
                    BlockBehaviour.Properties.of());

    /** 南瓜头扫描仪：右键果蝇脑方块生成 AI 玩家 */
    public static final DeferredHolder<Block, CarvedPumpkinHeadBlock> PUMPKIN_SCANNER =
            BLOCKS.registerBlock("pumpkin_scanner",
                    props -> new CarvedPumpkinHeadBlock(props.mapColor(MapColor.COLOR_ORANGE).strength(1.0f)),
                    BlockBehaviour.Properties.of());

    public static final DeferredHolder<Item, BlockItem> FLY_BRAIN_ITEM =
            ITEMS.registerSimpleBlockItem("fly_brain_block", FLY_BRAIN_BLOCK);
    public static final DeferredHolder<Item, BlockItem> PUMPKIN_SCANNER_ITEM =
            ITEMS.registerSimpleBlockItem("pumpkin_scanner", PUMPKIN_SCANNER);

    /** 触发物品：给玩家使用的召唤器（也可以直接用南瓜头扫描仪） */
    public static final DeferredHolder<Item, BrainPlayerSummonerItem> SUMMONER =
            ITEMS.registerItem("brain_player_summoner",
                    p -> new BrainPlayerSummonerItem(p), new Item.Properties().stacksTo(1));

    /** 头颅方块：决定 AI 的技能/性格/皮肤 */
    public static final DeferredHolder<Block, ProfileSkullBlock> PROFILE_SKULL =
            BLOCKS.registerBlock("profile_skull",
                    props -> new ProfileSkullBlock(
                            props.mapColor(MapColor.STONE).strength(1.0f).noOcclusion()),
                    BlockBehaviour.Properties.of());

    public static final DeferredHolder<Item, BlockItem> PROFILE_SKULL_ITEM =
            ITEMS.registerSimpleBlockItem("profile_skull", PROFILE_SKULL);

    /** 头颅选择盘：右键给单个头颅方块选类型（创造模式友好） */
    public static final DeferredHolder<Item, SkullSelectorItem> SKULL_SELECTOR =
            ITEMS.registerItem("skull_selector",
                    p -> new SkullSelectorItem(p), new Item.Properties().stacksTo(1));

    // ============ 实体 ============
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);

    /**
     * AI 玩家实体类型。
     *
     * 分类用 {@link MobCategory#CREATURE} —— 自然生成只对
     * CREATURE/MONSTER 等类别生效，MISC（盔甲架那类）根本不参与生成。
     *
     * 代价：受每区块 mob cap 约束（单区块约 380 上限）。
     * 但**自然生成**的 AI 会分散在世界各处，不像 summon 那样挤在一处，
     * 所以这个上限对"自由生成"场景影响不大。
     * 需要超大规模密集部署时再考虑改 MISC。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<BrainPlayerEntity>> BRAIN_PLAYER =
            ENTITIES.register("brain_player",
                    () -> EntityType.Builder.of(BrainPlayerEntity::new, MobCategory.CREATURE)
                            .sized(0.6f, 1.8f)
                            .eyeHeight(1.62f)
                            .clientTrackingRange(10)
                            .updateInterval(2)
                            .build("brain_player"));

    // ============ 创造栏 ============
    private static final ResourceKey<CreativeModeTab> REDSTONE_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    ResourceLocation.withDefaultNamespace("redstone_blocks"));

    // ============ 构造 ============
    public FlyBrainPlayerMod(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        ENTITIES.register(modEventBus);

        modEventBus.addListener(this::buildCreativeTab);
        modEventBus.addListener(this::registerAttributes);

        LOGGER.info("[FlyBrainPlayer] 果蝇脑 AI 玩家模组已加载 — Connectome 模拟器就绪");
    }

    // 知识库持久化在 KnowledgePersistence 里（game bus，独立监听器）

    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(BRAIN_PLAYER.get(), BrainPlayerEntity.createAttributes().build());
        LOGGER.info("[FlyBrainPlayer] BrainPlayer 属性已注册");
    }

    private void buildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(REDSTONE_TAB)) {
            event.accept(FLY_BRAIN_ITEM.get());
            event.accept(PUMPKIN_SCANNER_ITEM.get());
            event.accept(SKULL_SELECTOR.get());
            event.accept(SUMMONER.get());
            LOGGER.info("[FlyBrainPlayer] 已加入原版红石方块页");
        }
    }
}
