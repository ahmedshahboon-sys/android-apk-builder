extends CanvasLayer

const SAVE_PATH := "user://taxi_tripoli_upgrades.json"

var main: Node
var mission_manager: Node
var sun: DirectionalLight3D
var root: Control
var about_panel: PanelContainer
var garage_panel: PanelContainer
var status_label: Label
var time_label: Label
var engine_level := 0
var handling_level := 0
var day_clock := 0.25
var day_speed := 0.0025

func _ready() -> void:
    await get_tree().process_frame
    main = get_parent()
    mission_manager = main.get("mission_manager")
    sun = _find_sun()
    _load_upgrades()
    _apply_upgrades()
    _build_ui()

func _process(delta: float) -> void:
    if sun != null:
        day_clock = fmod(day_clock + delta * day_speed, 1.0)
        var angle := lerp(-75.0, 285.0, day_clock)
        sun.rotation_degrees.x = angle
        var daylight := clamp(sin(day_clock * TAU - PI * 0.5) * 0.5 + 0.5, 0.12, 1.0)
        sun.light_energy = daylight * 1.35
        if time_label != null:
            var hour := int(fmod(day_clock * 24.0 + 6.0, 24.0))
            var minute := int(fmod(day_clock * 24.0 * 60.0, 60.0))
            time_label.text = "%02d:%02d" % [hour, minute]

func _find_sun() -> DirectionalLight3D:
    for child in main.get_children():
        if child is DirectionalLight3D:
            return child
    return null

func _build_ui() -> void:
    root = Control.new()
    root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    root.mouse_filter = Control.MOUSE_FILTER_PASS
    add_child(root)

    time_label = Label.new()
    time_label.anchor_left = 0.46
    time_label.anchor_top = 0.03
    time_label.anchor_right = 0.54
    time_label.anchor_bottom = 0.08
    time_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    time_label.add_theme_font_size_override("font_size", 18)
    root.add_child(time_label)

    var garage_btn := Button.new()
    garage_btn.text = "الكراج"
    garage_btn.anchor_left = 0.84
    garage_btn.anchor_top = 0.19
    garage_btn.anchor_right = 0.91
    garage_btn.anchor_bottom = 0.27
    garage_btn.add_theme_font_size_override("font_size", 17)
    garage_btn.pressed.connect(func(): _toggle_panel(garage_panel))
    root.add_child(garage_btn)

    var about_btn := Button.new()
    about_btn.text = "عن اللعبة"
    about_btn.anchor_left = 0.915
    about_btn.anchor_top = 0.19
    about_btn.anchor_right = 0.985
    about_btn.anchor_bottom = 0.27
    about_btn.add_theme_font_size_override("font_size", 15)
    about_btn.pressed.connect(func(): _toggle_panel(about_panel))
    root.add_child(about_btn)

    garage_panel = _make_panel(Vector2(0.30,0.22), Vector2(0.70,0.76))
    garage_panel.visible = false
    var garage_box := VBoxContainer.new()
    garage_box.add_theme_constant_override("separation", 12)
    garage_panel.add_child(garage_box)

    var gt := Label.new()
    gt.text = "كراج تاكسي طرابلس"
    gt.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    gt.add_theme_font_size_override("font_size", 26)
    garage_box.add_child(gt)

    status_label = Label.new()
    status_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    status_label.add_theme_font_size_override("font_size", 18)
    garage_box.add_child(status_label)
    _refresh_status()

    var engine_btn := Button.new()
    engine_btn.text = "طوّر المحرك  —  150 د.ل"
    engine_btn.custom_minimum_size = Vector2(0,58)
    engine_btn.add_theme_font_size_override("font_size", 19)
    engine_btn.pressed.connect(_upgrade_engine)
    garage_box.add_child(engine_btn)

    var handling_btn := Button.new()
    handling_btn.text = "طوّر التحكم  —  120 د.ل"
    handling_btn.custom_minimum_size = Vector2(0,58)
    handling_btn.add_theme_font_size_override("font_size", 19)
    handling_btn.pressed.connect(_upgrade_handling)
    garage_box.add_child(handling_btn)

    var close_g := Button.new()
    close_g.text = "سكر الكراج"
    close_g.custom_minimum_size = Vector2(0,52)
    close_g.pressed.connect(func(): garage_panel.visible = false)
    garage_box.add_child(close_g)

    about_panel = _make_panel(Vector2(0.27,0.20), Vector2(0.73,0.78))
    about_panel.visible = false
    var about_box := VBoxContainer.new()
    about_box.add_theme_constant_override("separation", 10)
    about_panel.add_child(about_box)

    var at := Label.new()
    at.text = "تاكسي طرابلس"
    at.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    at.add_theme_font_size_override("font_size", 30)
    about_box.add_child(at)

    var credits := Label.new()
    credits.text = "تصميم وتطوير اللعبة\nأحمد شهبون\nAHMED SHAHBOUN\n\nللتواصل: 0921984045\n\nنسخة تجريبية — طرابلس، ليبيا"
    credits.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    credits.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    credits.size_flags_vertical = Control.SIZE_EXPAND_FILL
    credits.add_theme_font_size_override("font_size", 20)
    about_box.add_child(credits)

    var close_a := Button.new()
    close_a.text = "رجوع للعبة"
    close_a.custom_minimum_size = Vector2(0,52)
    close_a.pressed.connect(func(): about_panel.visible = false)
    about_box.add_child(close_a)

func _make_panel(from: Vector2, to: Vector2) -> PanelContainer:
    var panel := PanelContainer.new()
    panel.anchor_left = from.x
    panel.anchor_top = from.y
    panel.anchor_right = to.x
    panel.anchor_bottom = to.y
    root.add_child(panel)
    return panel

func _toggle_panel(panel: Control) -> void:
    if garage_panel != null and panel != garage_panel:
        garage_panel.visible = false
    if about_panel != null and panel != about_panel:
        about_panel.visible = false
    panel.visible = not panel.visible

func _upgrade_engine() -> void:
    if engine_level >= 3:
        status_label.text = "المحرك واصل آخر تطوير يا خوي"
        return
    if mission_manager == null or int(mission_manager.get("money")) < 150:
        status_label.text = "رصيدك ما يكفيش للتطوير"
        return
    mission_manager.set("money", int(mission_manager.get("money")) - 150)
    mission_manager.money_changed.emit(int(mission_manager.get("money")))
    engine_level += 1
    _apply_upgrades()
    _save_upgrades()
    _refresh_status()

func _upgrade_handling() -> void:
    if handling_level >= 3:
        status_label.text = "التحكم واصل آخر تطوير"
        return
    if mission_manager == null or int(mission_manager.get("money")) < 120:
        status_label.text = "رصيدك ما يكفيش للتطوير"
        return
    mission_manager.set("money", int(mission_manager.get("money")) - 120)
    mission_manager.money_changed.emit(int(mission_manager.get("money")))
    handling_level += 1
    _apply_upgrades()
    _save_upgrades()
    _refresh_status()

func _apply_upgrades() -> void:
    if main == null:
        return
    main.set("max_forward_speed", 25.0 + float(engine_level) * 2.5)
    main.set("acceleration", 10.5 + float(engine_level) * 1.2)
    main.set("steer_strength", 1.35 + float(handling_level) * 0.08)
    main.set("brake_force", 18.0 + float(handling_level) * 1.5)

func _refresh_status() -> void:
    if status_label != null:
        status_label.text = "المحرك: %d/3   |   التحكم: %d/3" % [engine_level, handling_level]

func _save_upgrades() -> void:
    var f := FileAccess.open(SAVE_PATH, FileAccess.WRITE)
    if f == null:
        return
    f.store_string(JSON.stringify({"engine_level":engine_level,"handling_level":handling_level}))

func _load_upgrades() -> void:
    if not FileAccess.file_exists(SAVE_PATH):
        return
    var f := FileAccess.open(SAVE_PATH, FileAccess.READ)
    if f == null:
        return
    var data = JSON.parse_string(f.get_as_text())
    if typeof(data) != TYPE_DICTIONARY:
        return
    engine_level = clamp(int(data.get("engine_level",0)),0,3)
    handling_level = clamp(int(data.get("handling_level",0)),0,3)
