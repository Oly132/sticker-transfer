package com.example.stickerpackbuilder.update

/** A dedicated provider avoids OEM issues with using the base FileProvider directly. */
class UpdateFiles : androidx.core.content.FileProvider()
