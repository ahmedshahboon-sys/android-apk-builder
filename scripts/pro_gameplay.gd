extends CanvasLayer

const SAVE_PATH := "user://taxi_tripoli_pro.json"

var main: Node
var mission_manager: Node
var audio_manager: Node
var taxi: Node3D
var camera: Camera3D

var root: Control
var status_panel: PanelContainer
var fuel_label: Label
var health_label: Label
var rating_label: Label
var trip_label: Label
var warning_label: Label

var fuel: float = 100.0
var health: float = 100.0
var rating: float = 5.0
var trip_seconds: float = 0.0
var trip_distance: float = 0.0
var last_position: Vector3 = Vector3.ZERO
var speeding_seconds: float = 0.0
var violation_cooldown: float = 0.0
var handbrake_active: bool = false
var camera_mode: int = 0

func _ready() -> void:
    await get_tree().process_frame
    main = get_parent()
    if main == null:
        return
    mission_manager = main.get("mission_manager")
    audio_manager = main.get("audio_manager")
    taxi = main.get("taxi") as Node3D
    camera = main.get("camera") as Camera3D
    if taxi != null:
        last_position = taxi.position
    _load_state()
    _build_ui()
    _refresh_labels()

func _process(delta: float) -> void:
    if main == null or taxi == null:
        return

    var speed_value: float = float(main.get("speed"))
    var speed_kmh: float = abs(speed_value) * 5.2
    var moved: float = taxi.position.distance_to(last_position)
    last_position = taxi.position
    trip_distance += moved
    trip_seconds += delta

    fuel = max(0.0, fuel - (0.0017 + speed_kmh * 0.000018) * delta * 10.0)
    if fuel <= 0.0:
        main.set("speed", move_toward(speed_value, 0.0, 16.0 * delta))
        _show_warning("البنزين خلص يا خوي.. عبّي من الزر")

    if handbrake_active:
        main.set("speed", move_toward(float(main.get("speed")), 0.0, 34.0 * delta))

    if speed_kmh > 115.0:
        speeding_seconds += delta
        if speeding_seconds > 3.0 and violation_cooldown <= 0.0:
            _apply_violation("سرعة زايدة — مخالفة 10 د.ل")
            speeding_seconds = 0.0
            violation_cooldown = 8.0
    else:
        speeding_seconds = max(0.0, speeding_seconds - delta * 1.5)

    if violation_cooldown > 0.0:
        violation_cooldown -= delta

    _refresh_labels()

func _build_ui() -> void:
    root = Control.new()
    root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    root.mouse_filter = Control.MOUSE_FILTER_PASS
    add_child(root)

    status_panel = PanelContainer.new()
    status_panel.anchor_left = 0.015
    status_panel.anchor_top = 0.11
    status_panel.anchor_right = 0.22
    status_panel.anchor_bottom = 0.31
    root.add_child(status_panel)

    var box: VBoxContainer = VBoxContainer.new()
    box.add_theme_constant_override("separation", 2)
    status_panel.add_child(box)

    fuel_label = _label(16)
    health_label = _label(16)
    rating_label = _label(16)
    trip_label = _label(15)
    box.add_child(fuel_label)
    box.add_child(health_label)
    box.add_child(rating_label)
    box.add_child(trip_label)

    warning_label = Label.new()
    warning_label.anchor_left = 0.31
    warning_label.anchor_top = 0.11
    warning_label.anchor_right = 0.69
    warning_label.anchor_bottom = 0.18
    warning_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    warning_label.add_theme_font_size_override("font_size", 18)
    warning_label.modulate = Color(1.0,0.78,0.30)
    root.add_child(warning_label)

    var refuel: Button = _button("⛽ عبّي 35 د.ل", Vector2(0.015,0.32), Vector2(0.125,0.40))
    refuel.pressed.connect(_refuel)

    var camera_btn: Button = _button("📷 كاميرا", Vector2(0.13,0.32), Vector2(0.22,0.40))
    camera_btn.pressed.connect(_cycle_camera)

    var horn_btn: Button = _button("📣 بوري", Vector2(0.79,0.70), Vector2(0.88,0.80))
    horn_btn.pressed.connect(_horn)

    var handbrake_btn: Button = _button("🅿 فرامل يد", Vector2(0.885,0.70), Vector2(0.985,0.80))
    handbrake_btn.button_down.connect(func() -> void: handbrake_active = true)
    handbrake_btn.button_up.connect(func() -> void: handbrake_active = false)

func _label(font_size: int) -> Label:
    var label: Label = Label.new()
    label.add_theme_font_size_override("font_size", font_size)
    label.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
    return label

func _button(text_value: String, from: Vector2, to: Vector2) -> Button:
    var button: Button = Button.new()
    button.text = text_value
    button.anchor_left = from.x
    button.anchor_top = from.y
    button.anchor_right = to.x
    button.anchor_bottom = to.y
    button.add_theme_font_size_override("font_size", 16)
    root.add_child(button)
    return button

func _refresh_labels() -> void:
    if fuel_label == null:
        return
    fuel_label.text = "بنزين: %d%%" % int(fuel)
    health_label.text = "حالة التاكسي: %d%%" % int(health)
    rating_label.text = "تقييمك: %.1f ★" % rating
    var minutes: int = int(trip_seconds) / 60
    var seconds: int = int(trip_seconds) % 60
    trip_label.text = "المشوار: %02d:%02d  |  %.1f كم" % [minutes,seconds,trip_distance / 1000.0]

func _refuel() -> void:
    if fuel > 94.0:
        _show_warning("الخزان تقريبًا فل")
        return
    if mission_manager == null:
        return
    var current_money: int = int(mission_manager.get("money"))
    if current_money < 35:
        _show_warning("رصيدك ما يكفيش للبنزين")
        return
    mission_manager.set("money", current_money - 35)
    mission_manager.money_changed.emit(current_money - 35)
    fuel = 100.0
    _save_state()
    _show_warning("تمت التعبية.. توكل على الله")

func _cycle_camera() -> void:
    if camera == null:
        return
    camera_mode = (camera_mode + 1) % 3
    if camera_mode == 0:
        camera.position = Vector3(0,3.25,6.6)
        camera.rotation_degrees = Vector3(-14,0,0)
        camera.fov = 72.0
    elif camera_mode == 1:
        camera.position = Vector3(0,1.45,0.35)
        camera.rotation_degrees = Vector3(-3,180,0)
        camera.fov = 76.0
    else:
        camera.position = Vector3(0,5.6,8.8)
        camera.rotation_degrees = Vector3(-23,0,0)
        camera.fov = 68.0
    _show_warning("بدّلنا زاوية الكاميرا")

func _horn() -> void:
    if audio_manager != null:
        audio_manager.horn()

func _apply_violation(message: String) -> void:
    health = max(0.0, health - 2.0)
    rating = max(1.0, rating - 0.15)
    if mission_manager != null:
        var current_money: int = int(mission_manager.get("money"))
        var new_money: int = max(0, current_money - 10)
        mission_manager.set("money", new_money)
        mission_manager.money_changed.emit(new_money)
    if audio_manager != null:
        audio_manager.error()
    _show_warning(message)
    _save_state()

func _show_warning(text_value: String) -> void:
    if warning_label == null:
        return
    warning_label.text = text_value
    var timer: SceneTreeTimer = get_tree().create_timer(2.2)
    timer.timeout.connect(func() -> void:
        if warning_label != null and warning_label.text == text_value:
            warning_label.text = ""
    )

func _save_state() -> void:
    var file: FileAccess = FileAccess.open(SAVE_PATH, FileAccess.WRITE)
    if file == null:
        return
    file.store_string(JSON.stringify({"fuel":fuel,"health":health,"rating":rating,"camera_mode":camera_mode}))

func _load_state() -> void:
    if not FileAccess.file_exists(SAVE_PATH):
        return
    var file: FileAccess = FileAccess.open(SAVE_PATH, FileAccess.READ)
    if file == null:
        return
    var data: Variant = JSON.parse_string(file.get_as_text())
    if typeof(data) != TYPE_DICTIONARY:
        return
    var dictionary: Dictionary = data as Dictionary
    fuel = clamp(float(dictionary.get("fuel",100.0)),0.0,100.0)
    health = clamp(float(dictionary.get("health",100.0)),0.0,100.0)
    rating = clamp(float(dictionary.get("rating",5.0)),1.0,5.0)
    camera_mode = int(dictionary.get("camera_mode",0)) % 3
