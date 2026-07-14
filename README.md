# Better Aerodymanics

**English** | [简体中文](./README.zh-CN.md)

## Version

This is a mod for Minecraft 26.1.2 with fabric 0.19.3. 

## What it does

This mod provides a basic aerodynamic implementation in Minecraft for elytra. In this mod, it is assumed that elytra has an airfoil of NACA 2412 (though any NACA four digit airfoil is supported). By reading the player's speed in real-time, the mod is able to calculate the boundary layer on the elytra and thus use panel method and 3D wing correction to solve for lift and drag. The goal is to provide a more realistic flying experience.

Together with aerodynamics is the impelemtation of ISA conditions. Currently, it is set to world sea level (y=70) being sea level, and world build limit (y=320) being the height of Mt.Everest. This conversion is unique to air property calculation, with the rest aero calculation still using the standard conversion (1 block = 1 meter). This mod also provides a simple HUD showing the current height of the player with the player's vertical speed, in "aviation unit", which is feet and feet per minute. 

![In Game Screenshot1](pic/2026-07-14_12.15.19.png)
![In Game Screenshot2](pic/2026-07-14_12.15.33.png)
![In Game Screenshot3](pic/2026-07-14_12.15.44.png)

## WHY

This is what happens when an aero student felt crazy.

## Version Download

[Downloads can be found here]()
