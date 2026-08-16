extends Node

var engine_player: AudioStreamPlayer
var ambience_player: AudioStreamPlayer
var sfx_player: AudioStreamPlayer
var ui_player: AudioStreamPlayer
var engine_stream: AudioStreamWAV

func _ready() -> void:
    engine_player = AudioStreamPlayer.new()
    ambience_player = AudioStreamPlayer.new()
    sfx_player = AudioStreamPlayer.new()
    ui_player = AudioStreamPlayer.new()
    add_child(engine_player)
    add_child(ambience_player)
    add_child(sfx_player)
    add_child(ui_player)

    engine_stream = _make_tone(86.0, 1.2, 0.26, true)
    engine_player.stream = engine_stream
    engine_player.volume_db = -14.0
    engine_player.play()

    ambience_player.stream = _make_noise(2.0, 0.08, true)
    ambience_player.volume_db = -23.0
    ambience_player.play()

func update_engine(speed_ratio: float, accelerating: bool) -> void:
    if engine_player == null:
        return
    var r: float = clampf(absf(speed_ratio), 0.0, 1.0)
    engine_player.pitch_scale = 0.82 + r * 1.65 + (0.10 if accelerating else 0.0)
    engine_player.volume_db = -17.0 + r * 7.0

func ui_click() -> void:
    _play_ui(_make_tone(620.0, 0.055, 0.20, false))

func pickup() -> void:
    _play_sfx(_make_chime([440.0, 590.0], 0.11, 0.22))

func reward() -> void:
    _play_sfx(_make_chime([520.0, 690.0, 880.0], 0.10, 0.24))

func horn() -> void:
    _play_sfx(_make_tone(310.0, 0.28, 0.34, false))

func brake() -> void:
    _play_sfx(_make_noise(0.18, 0.18, false))

func error() -> void:
    _play_ui(_make_chime([190.0, 145.0], 0.12, 0.20))

func _play_sfx(stream: AudioStream) -> void:
    sfx_player.stream = stream
    sfx_player.volume_db = -8.0
    sfx_player.play()

func _play_ui(stream: AudioStream) -> void:
    ui_player.stream = stream
    ui_player.volume_db = -10.0
    ui_player.play()

func _make_tone(freq: float, seconds: float, gain: float, looped: bool) -> AudioStreamWAV:
    var rate: int = 22050
    var count: int = int(seconds * float(rate))
    var bytes := PackedByteArray()
    bytes.resize(count)
    for i in range(count):
        var t: float = float(i) / float(rate)
        var envelope: float = 1.0
        if not looped:
            var attack: float = minf(1.0, t / 0.015)
            var release: float = minf(1.0, (seconds - t) / 0.05)
            envelope = minf(attack, release)
        var harmonic: float = sin(TAU * freq * t) * 0.75 + sin(TAU * freq * 2.0 * t) * 0.18
        var sample_value: int = int(clampf(128.0 + harmonic * gain * envelope * 120.0, 0.0, 255.0))
        bytes[i] = sample_value
    var wav := AudioStreamWAV.new()
    wav.format = AudioStreamWAV.FORMAT_8_BITS
    wav.mix_rate = rate
    wav.stereo = false
    wav.data = bytes
    if looped:
        wav.loop_mode = AudioStreamWAV.LOOP_FORWARD
        wav.loop_begin = 0
        wav.loop_end = count
    return wav

func _make_noise(seconds: float, gain: float, looped: bool) -> AudioStreamWAV:
    var rate: int = 22050
    var count: int = int(seconds * float(rate))
    var bytes := PackedByteArray()
    bytes.resize(count)
    var smooth: float = 0.0
    for i in range(count):
        var raw: float = randf_range(-1.0, 1.0)
        smooth = lerpf(smooth, raw, 0.08)
        var sample_value: int = int(clampf(128.0 + smooth * gain * 120.0, 0.0, 255.0))
        bytes[i] = sample_value
    var wav := AudioStreamWAV.new()
    wav.format = AudioStreamWAV.FORMAT_8_BITS
    wav.mix_rate = rate
    wav.stereo = false
    wav.data = bytes
    if looped:
        wav.loop_mode = AudioStreamWAV.LOOP_FORWARD
        wav.loop_begin = 0
        wav.loop_end = count
    return wav

func _make_chime(freqs: Array, note_seconds: float, gain: float) -> AudioStreamWAV:
    var rate: int = 22050
    var total_seconds: float = note_seconds * float(freqs.size())
    var count: int = int(total_seconds * float(rate))
    var bytes := PackedByteArray()
    bytes.resize(count)
    for i in range(count):
        var t: float = float(i) / float(rate)
        var note_index: int = mini(int(t / note_seconds), freqs.size() - 1)
        var local_t: float = fmod(t, note_seconds)
        var freq: float = float(freqs[note_index])
        var envelope: float = minf(1.0, local_t / 0.01) * minf(1.0, (note_seconds - local_t) / 0.04)
        var wave: float = sin(TAU * freq * local_t)
        bytes[i] = int(clampf(128.0 + wave * gain * envelope * 120.0, 0.0, 255.0))
    var wav := AudioStreamWAV.new()
    wav.format = AudioStreamWAV.FORMAT_8_BITS
    wav.mix_rate = rate
    wav.stereo = false
    wav.data = bytes
    return wav
