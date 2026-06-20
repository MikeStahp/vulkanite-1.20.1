execute as @a at @s if block ~ ~-1 ~ cyan_concrete run tag @s add vulkanite_traversing
execute as @a[tag=vulkanite_traversing] at @s run tp @s ~2 ~ ~
execute as @a[tag=vulkanite_traversing] at @s if block ~ -60 ~ air run tag @s remove vulkanite_traversing
