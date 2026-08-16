extends Node

const SAVE_PATH := "user://taxi_tripoli_progress.json"

var root: Node
var credits_layer: CanvasLayer
var garage_layer: CanvasLayer
var sun: DirectionalLight3D
var day_t := 0.27
var engine_level := 1
var handling_level := 1

func _ready() -> void:
    await get_tree().process_frame
    root = get_tree().current_scene
    if root == null:
        return
    _find_sun()
    _load_progress()
    _build_quick_menu()

func _process(delta: float) -> void:
    _update_day_night(delta)

func _find_sun() -> void:
    for child in root.get_children():
        if child is DirectionalLight3D:
            sun = child
            break

func _update_day_night(delta: float) -> void:
    if sun == null:
        return
    day_t = fmod(day_t + delta / 420.0, 1.0)
    var angle := lerp(-75.0, 285.0, day_t)
    sun.rotation_degrees.x = angle
    var daylight := clamp(sin(day_t * TAU - PI * 0.5) * 0.5 + 0.5, 0.08, 1.0)
    sun.light_energy = lerp(0.18, 1.3, daylight)

func _build_quick_menu() -> void:
    var layer := CanvasLayer.new()
    layer.layer = 20
    add_child(layer)

    var about := Button.new()
    about.text = "عن اللعبة"
    about.anchor_left = 0.84
    about.anchor_top = 0.02
    about.anchor_right = 0.92
    about.anchor_bottom = 0.09
    about.add_theme_font_size_override("font_size", 16)
    about.pressed.connect(_toggle_credits)
    layer.add_child(about)

    var garage := Button.new()
    garage.text = "الكراج"
    garage.anchor_left = 0.93
    garage.anchor_top = 0.02
    garage.anchor_right = 0.99
    garage.anchor_bottom = 0.09
    garage.add_theme_font_size_override("font_size", 16)
    garage.pressed.connect(_toggle_garage)
    layer.add_child(garage)

func _toggle_credits() -> void:
    if credits_layer == null:
        _build_credits()
    credits_layer.visible = not credits_layer.visible

func _build_credits() -> void:
    credits_layer = CanvasLayer.new()
    credits_layer.layer = 50
    add_child(credits_layer)

    var bg := ColorRect.new()
    bg.color = Color(0.02,0.025,0.03,0.94)
    bg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    credits_layer.add_child(bg)

    var panel := VBoxContainer.new()
    panel.anchor_left = 0.22
    panel.anchor_top = 0.18
    panel.anchor_right = 0.78
    panel.anchor_bottom = 0.82
    panel.alignment = BoxContainer.ALIGNMENT_CENTER
    panel.add_theme_constant_override("separation", 14)
    credits_layer.add_child(panel)

    var title := Label.new()
    title.text = "تاكسي طرابلس"
    title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    title.add_theme_font_size_override("font_size", 36)
    panel.add_child(title)

    var dev := Label.new()
    dev.text = "تصميم وتطوير اللعبة\nأحمد شهبون\nAHMED SHAHBOUN\nللتواصل: 0921984045"
    dev.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    dev.add_theme_font_size_override("font_size", 24)
    panel.add_child(dev)

    var rights := Label.new()
    rights.text = "جميع حقوق التصميم والتطوير محفوظة للمطور"
    rights.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    rights.add_theme_font_size_override("font_size", 18)
    panel.add_child(rights)

    var close := Button.new()
    close.text = "سكر"
    close.custom_minimum_size = Vector2(220,56)
    close.pressed.connect(_toggle_credits)
    panel.add_child(close)
    credits_layer.visible = true

func _toggle_garage() -> void:
    if garage_layer == null:
        _build_garage()
    garage_layer.visible = not garage_layer.visible

func _build_garage() -> void:
    garage_layer = CanvasLayer.new()
    garage_layer.layer = 45
    add_child(garage_layer)

    var bg := ColorRect.new()
    bg.color = Color(0.03,0.035,0.04,0.94)
    bg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    garage_layer.add_child(bg)

    var box := VBoxContainer.new()
    box.anchor_left = 0.28
    box.anchor_top = 0.18
    box.anchor_right = 0.72
    box.anchor_bottom = 0.82
    box.alignment = BoxContainer.ALIGNMENT_CENTER
    box.add_theme_constant_override("separation", 16)
    garage_layer.add_child(box)

    var title := Label.new()
    title.text = "كراج تاكسي طرابلس"
    title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    title.add_theme_font_size_override("font_size", 32)
    box.add_child(title)

    var info := Label.new()
    info.name = "Info"
    info.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    info.add_theme_font_size_override("font_size", 21)
    box.add_child(info)

    var eng := Button.new()
    eng.text = "طوّر المحرك - 100 د.ل"
    eng.custom_minimum_size = Vector2(360,60)
    eng.pressed.connect(_upgrade_engine)
    box.add_child(eng)

    var hand := Button.new()
    hand.text = "طوّر التحكم - 80 د.ل"
    hand.custom_minimum_size = Vector2(360,60)
    hand.pressed.connect(_upgrade_handling)
    box.add_child(hand)

    var close := Button.new()
    close.text = "اطلع من الكراج"
    close.custom_minimum_size = Vector2(300,56)
    close.pressed.connect(_toggle_garage)
    box.add_child(close)
    _refresh_garage()
    garage_layer.visible = true

func _get_mission_manager() -> Node:
    if root == null:
        return null
    return root.get("mission_manager")

func _upgrade_engine() -> void:
    var mm := _get_mission_manager()
    if mm == null or int(mm.get("money")) < 100:
        return
    mm.set("money", int(mm.get("money")) - 100)
    engine_level += 1
    root.set("max_forward_speed", 25.0 + float(engine_level - 1) * 2.0)
    mm.money_changed.emit(int(mm.get("money")))
    _save_progress()
    _refresh_garage()

func _upgrade_handling() -> void:
    var mm := _get_mission_manager()
    if mm == null or int(mm.get("money")) < 80:
        return
    mm.set("money", int(mm.get("money")) - 80)
    handling_level += 1
    root.set("steer_strength", 1.35 + float(handling_level - 1) * 0.08)
    mm.money_changed.emit(int(mm.get("money")))
    _save_progress()
    _refresh_garage()

func _refresh_garage() -> void:
    if garage_layer == null:
        return
    var info := garage_layer.get_node_or_null("VBoxContainer/Info")
    if info == null:
        for c in garage_layer.get_children():
            if c is VBoxContainer:
                info = c.get_node_or_null("Info")
                break
    if info:
        info.text = "مستوى المحرك: %d\nمستوى التحكم: %d" % [engine_level, handling_level]

func _load_progress() -> void:
    if not FileAccess.file_exists(SAVE_PATH):
        return
    var f := FileAccess.open(SAVE_PATH, FileAccess.READ)
    if f == null:
        return
    var parsed = JSON.parse_string(f.get_as_text())
    if typeof(parsed) != TYPE_DICTIONARY:
        return
    engine_level = int(parsed.get("engine_level", 1))
    handling_level = int(parsed.get("handling_level", 1))
    root.set("max_forward_speed", 25.0 + float(engine_level - 1) * 2.0)
    root.set("steer_strength", 1.35 + float(handling_level - 1) * 0.08)

func _save_progress() -> void:
    var f := FileAccess.open(SAVE_PATH, FileAccess.WRITE)
    if f == null:
        return
    f.store_string(JSON.stringify({
        "engine_level": engine_level,
        "handling_level": handling_level
    }))
