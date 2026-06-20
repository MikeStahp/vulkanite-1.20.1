# Freeze the environment before constructing the course.
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule randomTickSpeed 0
time set 6000
weather clear 1000000

# Keep the complete build area loaded, then remove any partial earlier build.
forceload add 480 -48 992 48
kill @e[tag=vulkanite_benchmark]
fill 480 -59 -48 495 -43 48 air
fill 496 -59 -48 511 -43 48 air
fill 512 -59 -48 527 -43 48 air
fill 528 -59 -48 543 -43 48 air
fill 544 -59 -48 559 -43 48 air
fill 560 -59 -48 575 -43 48 air
fill 576 -59 -48 591 -43 48 air
fill 592 -59 -48 607 -43 48 air
fill 608 -59 -48 608 -43 48 air

# Shared foundation and outdoor material/emitter stage.
fill 480 -60 -48 608 -60 48 smooth_stone
fill 484 -59 -20 520 -59 20 smooth_quartz
fill 520 -58 -20 520 -44 20 white_concrete
fill 508 -58 -17 512 -50 -13 iron_block
fill 508 -58 -10 512 -50 -6 gold_block
fill 508 -58 -3 512 -50 1 copper_block
fill 508 -58 4 512 -50 8 quartz_block
fill 508 -58 11 512 -50 15 polished_blackstone
fill 496 -58 8 503 -51 15 glass hollow
fill 498 -57 10 501 -53 13 water
fill 516 -55 -16 516 -51 -12 sea_lantern
fill 516 -55 -9 516 -51 -5 ochre_froglight
fill 516 -55 -2 516 -51 2 shroomlight
fill 516 -55 5 516 -51 9 glowstone
fill 516 -55 12 516 -51 16 redstone_block

# Sealed indoor lab. The black divider separates a lit room from a dark room.
fill 532 -59 -16 564 -43 16 gray_concrete hollow
fill 532 -58 -2 532 -53 2 air
fill 548 -58 -13 548 -44 13 black_concrete
fill 548 -58 -2 548 -53 2 air
fill 536 -58 -12 544 -58 12 polished_andesite
fill 552 -58 -12 560 -58 12 deepslate_tiles
fill 545 -55 -10 545 -51 10 white_concrete
fill 546 -55 -10 546 -51 -6 sea_lantern
fill 546 -55 -3 546 -51 1 ochre_froglight
fill 546 -55 4 546 -51 8 shroomlight
setblock 540 -43 -8 sea_lantern
setblock 540 -43 0 sea_lantern
setblock 540 -43 8 sea_lantern
fill 552 -57 -11 560 -50 -10 tinted_glass
fill 557 -57 -9 560 -54 -6 water

# Colored camera pads: yellow outdoor, lime lit interior, red dark interior,
# blue materials, magenta entities, cyan traversal, orange stress entities, purple volumetric room
setblock 486 -59 0 yellow_concrete
setblock 536 -59 0 lime_concrete
setblock 556 -59 0 red_concrete
setblock 492 -59 18 blue_concrete
setblock 476 -59 36 magenta_concrete
setblock 608 -59 0 cyan_concrete
setblock 476 -59 52 orange_concrete
setblock 580 -59 0 purple_concrete

# Bounded animated-entity scene (8 entities).
fill 484 -59 28 510 -59 44 grass_block
fill 484 -58 28 510 -58 28 oak_fence
fill 484 -58 44 510 -58 44 oak_fence
fill 484 -58 29 484 -58 43 oak_fence
fill 510 -58 29 510 -58 43 oak_fence
summon cow 490 -58 34 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon cow 494 -58 34 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon cow 498 -58 34 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon sheep 502 -58 34 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon sheep 506 -58 34 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon pig 492 -58 39 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon pig 498 -58 39 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon pig 504 -58 39 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}

# Stress-test entity scene (32 entities + equipment).
fill 484 -59 46 510 -59 62 grass_block
fill 484 -58 46 510 -58 46 oak_fence
fill 484 -58 62 510 -58 62 oak_fence
fill 484 -58 47 484 -58 61 oak_fence
fill 510 -58 47 510 -58 61 oak_fence
# Rows of animals
summon cow 488 -58 50 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon cow 491 -58 50 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon cow 494 -58 50 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon cow 497 -58 50 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon sheep 500 -58 50 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon sheep 503 -58 50 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon sheep 506 -58 50 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon pig 489 -58 54 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon pig 492 -58 54 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon pig 495 -58 54 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon pig 498 -58 54 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon chicken 501 -58 54 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon chicken 504 -58 54 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon chicken 507 -58 54 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
# Armor stands with equipment to stress distinct meshes/materials
summon armor_stand 490 -58 58 {ShowArms:1b,ArmorItems:[{id:"iron_boots",Count:1b},{id:"iron_leggings",Count:1b},{id:"iron_chestplate",Count:1b},{id:"iron_helmet",Count:1b}],HandItems:[{id:"iron_sword",Count:1b},{}],PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon armor_stand 492 -58 58 {ShowArms:1b,ArmorItems:[{id:"gold_boots",Count:1b},{id:"gold_leggings",Count:1b},{id:"gold_chestplate",Count:1b},{id:"gold_helmet",Count:1b}],HandItems:[{id:"golden_sword",Count:1b},{}],PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon armor_stand 494 -58 58 {ShowArms:1b,ArmorItems:[{id:"diamond_boots",Count:1b},{id:"diamond_leggings",Count:1b},{id:"diamond_chestplate",Count:1b},{id:"diamond_helmet",Count:1b}],HandItems:[{id:"diamond_sword",Count:1b},{}],PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon armor_stand 496 -58 58 {ShowArms:1b,ArmorItems:[{id:"netherite_boots",Count:1b},{id:"netherite_leggings",Count:1b},{id:"netherite_chestplate",Count:1b},{id:"netherite_helmet",Count:1b}],HandItems:[{id:"netherite_sword",Count:1b},{}],PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon armor_stand 498 -58 58 {ShowArms:1b,ArmorItems:[{id:"leather_boots",Count:1b},{id:"leather_leggings",Count:1b},{id:"leather_chestplate",Count:1b},{id:"leather_helmet",Count:1b}],HandItems:[{id:"bow",Count:1b},{}],PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon armor_stand 500 -58 58 {ShowArms:1b,ArmorItems:[{id:"chainmail_boots",Count:1b},{id:"chainmail_leggings",Count:1b},{id:"chainmail_chestplate",Count:1b},{id:"chainmail_helmet",Count:1b}],HandItems:[{id:"crossbow",Count:1b},{}],PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon armor_stand 502 -58 58 {ShowArms:1b,ArmorItems:[{id:"iron_boots",Count:1b},{id:"iron_leggings",Count:1b},{id:"iron_chestplate",Count:1b},{id:"iron_helmet",Count:1b}],HandItems:[{id:"shield",Count:1b},{}],PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon armor_stand 504 -58 58 {ShowArms:1b,ArmorItems:[{id:"diamond_boots",Count:1b},{id:"diamond_leggings",Count:1b},{id:"diamond_chestplate",Count:1b},{id:"diamond_helmet",Count:1b}],HandItems:[{id:"trident",Count:1b},{}],PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
# Some extra mixed entities
summon villager 487 -58 57 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon villager 487 -58 59 {PersistenceRequired:1b,Tags:["vulkanite_benchmark"]}
summon zombie 507 -58 57 {PersistenceRequired:1b,NoAI:1b,Tags:["vulkanite_benchmark"]}
summon skeleton 507 -58 59 {PersistenceRequired:1b,NoAI:1b,Tags:["vulkanite_benchmark"]}
summon creeper 507 -58 61 {PersistenceRequired:1b,NoAI:1b,Tags:["vulkanite_benchmark"]}
summon spider 487 -58 61 {PersistenceRequired:1b,NoAI:1b,Tags:["vulkanite_benchmark"]}
summon enderman 496 -58 60 {PersistenceRequired:1b,NoAI:1b,Tags:["vulkanite_benchmark"]}
summon slime 496 -58 52 {Size:2,PersistenceRequired:1b,NoAI:1b,Tags:["vulkanite_benchmark"]}

# Enclosed volumetric fog room
fill 576 -59 -8 592 -43 8 black_concrete hollow
fill 576 -58 -2 576 -53 2 air
fill 577 -58 -7 591 -58 7 powder_snow
setblock 588 -58 0 soul_campfire
setblock 588 -58 -4 soul_campfire
setblock 588 -58 4 soul_campfire

# Chunk-traversal runway with one light marker every two chunks.
# Also added colored wool markers every 32 blocks to verify consistent trajectory.
fill 608 -60 -8 992 -60 8 stone
setblock 640 -59 0 sea_lantern
setblock 672 -59 0 sea_lantern
setblock 704 -59 0 sea_lantern
setblock 736 -59 0 sea_lantern
setblock 768 -59 0 sea_lantern
setblock 800 -59 0 sea_lantern
setblock 832 -59 0 sea_lantern
setblock 864 -59 0 sea_lantern
setblock 896 -59 0 sea_lantern
setblock 928 -59 0 sea_lantern
setblock 960 -59 0 sea_lantern

setblock 640 -59 -2 red_wool
setblock 672 -59 -2 orange_wool
setblock 704 -59 -2 yellow_wool
setblock 736 -59 -2 lime_wool
setblock 768 -59 -2 green_wool
setblock 800 -59 -2 cyan_wool
setblock 832 -59 -2 light_blue_wool
setblock 864 -59 -2 blue_wool
setblock 896 -59 -2 purple_wool
setblock 928 -59 -2 magenta_wool
setblock 960 -59 -2 pink_wool

setworldspawn 486 -58 0 -90
data modify storage vulkanite_benchmark:state built set value 1b
forceload remove 480 -48 992 48
tellraw @a [{"text":"Vulkanite benchmark lab built. Use /function vulkanite_benchmark:camera/outdoor","color":"aqua"}]
