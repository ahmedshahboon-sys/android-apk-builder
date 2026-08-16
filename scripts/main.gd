extends Node3D

const AudioManagerScript = preload("res://scripts/audio_manager.gd")
const MissionManagerScript = preload("res://scripts/mission_manager.gd")

var taxi: Node3D
var camera: Camera3D
var target_marker: MeshInstance3D
var audio_manager: Node
var mission_manager: Node

var speed := 0.0
var steer_input := 0.0
var throttle_input := 0.0
var max_forward_speed := 25.0
var max_reverse_speed := 8.0
var acceleration := 10.5
var brake_force := 18.0
var coast_drag := 5.0
var steer_strength := 1.35
var last_braking := false

var info_label: Label
var money_label: Label
var speed_label: Label
var area_label: Label

func _ready() -> void:
    DisplayServer.screen_set_orientation(DisplayServer.SCREEN_LANDSCAPE)
    _build_environment()
    _build_city()
    _build_taxi()
    _build_game_systems()
    _build_ui()

func _process(delta: float) -> void:
    _update_driving(delta)
    mission_manager.update_position(taxi.position)
    _update_target_marker()
    audio_manager.update_engine(abs(speed) / max_forward_speed, throttle_input > 0.1)

func _update_driving(delta: float) -> void:
    var keyboard_throttle := Input.get_axis("move_back", "move_forward")
    var keyboard_steer := Input.get_axis("move_right", "move_left")
    var throttle := throttle_input if abs(throttle_input) > 0.01 else keyboard_throttle
    var steering := steer_input if abs(steer_input) > 0.01 else keyboard_steer

    if throttle > 0.05:
        speed = move_toward(speed, max_forward_speed, acceleration * delta)
    elif throttle < -0.05:
        if speed > 1.0:
            speed = move_toward(speed, 0.0, brake_force * delta)
            if not last_braking:
                audio_manager.brake()
            last_braking = true
        else:
            speed = move_toward(speed, -max_reverse_speed, acceleration * 0.65 * delta)
    else:
        speed = move_toward(speed, 0.0, coast_drag * delta)
        last_braking = false

    var speed_ratio := clamp(abs(speed) / max_forward_speed, 0.0, 1.0)
    if abs(speed) > 0.2:
        var steering_at_speed := lerp(1.0, 0.48, speed_ratio)
        taxi.rotate_y(steering * steer_strength * steering_at_speed * delta * sign(speed))

    taxi.position += -taxi.transform.basis.z * speed * delta
    speed_label.text = "%d كم/س" % int(abs(speed) * 5.2)

func _build_game_systems() -> void:
    audio_manager = AudioManagerScript.new()
    add_child(audio_manager)

    mission_manager = MissionManagerScript.new()
    add_child(mission_manager)
    mission_manager.mission_text_changed.connect(_on_mission_text)
    mission_manager.money_changed.connect(_on_money_changed)
    mission_manager.pickup_sound.connect(func(): audio_manager.pickup())
    mission_manager.reward_sound.connect(func(): audio_manager.reward())

func _on_mission_text(text: String) -> void:
    if info_label != null:
        info_label.text = text

func _on_money_changed(value: int) -> void:
    if money_label != null:
        money_label.text = "%d د.ل" % value

func _build_environment() -> void:
    var env := WorldEnvironment.new()
    var e := Environment.new()
    e.background_mode = Environment.BG_COLOR
    e.background_color = Color(0.36, 0.66, 0.91)
    e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
    e.ambient_light_color = Color(1.0, 0.93, 0.80)
    e.ambient_light_energy = 0.78
    e.tonemap_mode = Environment.TONE_MAPPER_FILMIC
    env.environment = e
    add_child(env)

    var sun := DirectionalLight3D.new()
    sun.rotation_degrees = Vector3(-54, -28, 0)
    sun.light_energy = 1.25
    sun.shadow_enabled = true
    sun.directional_shadow_max_distance = 95.0
    add_child(sun)

func _build_city() -> void:
    # Terrain and asphalt.
    _box(Vector3(0,-0.20,0), Vector3(200,0.35,160), Color(0.69,0.63,0.51))
    _box(Vector3(0,0.00,8), Vector3(170,0.11,22), Color(0.055,0.060,0.065))
    _box(Vector3(31,0.01,-15), Vector3(22,0.11,125), Color(0.055,0.060,0.065))
    _box(Vector3(-30,0.02,32), Vector3(125,0.11,20), Color(0.055,0.060,0.065))

    # Curbs / pavements.
    for z in [-4.0, 20.0]:
        _box(Vector3(0,0.14,z), Vector3(165,0.25,2.7), Color(0.88,0.86,0.78))
    for x in [19.0, 43.0]:
        _box(Vector3(x,0.14,-18), Vector3(2.7,0.25,115), Color(0.88,0.86,0.78))

    # Road markings.
    for x in range(-74, 75, 11):
        _box(Vector3(x,0.075,8), Vector3(5.0,0.025,0.20), Color(0.96,0.91,0.70))
    for z in range(-62, 49, 11):
        _box(Vector3(31,0.076,z), Vector3(0.20,0.025,5.0), Color(0.96,0.91,0.70))

    # Red Castle stylised reconstruction.
    _castle_block(Vector3(-34,6.2,-31), Vector3(58,12.4,21), Color(0.57,0.35,0.20))
    _castle_tower(Vector3(-56,9.3,-24))
    _castle_tower(Vector3(-12,9.0,-24))
    _box(Vector3(-34,3.4,-20.25), Vector3(8.5,6.8,1.1), Color(0.095,0.075,0.060))
    _box(Vector3(-34,9.7,-20.15), Vector3(17,0.6,1.2), Color(0.67,0.43,0.25))

    # City blocks with storefronts.
    _building(Vector3(52,5.5,-42), Vector3(15,11,12), Color(0.82,0.71,0.56), "قهوة طرابلس")
    _building(Vector3(68,6.5,-25), Vector3(14,13,12), Color(0.74,0.66,0.54), "مخبز")
    _building(Vector3(67,5.5,18), Vector3(15,11,13), Color(0.86,0.78,0.65), "صيدلية")
    _building(Vector3(53,6.5,41), Vector3(16,13,12), Color(0.76,0.70,0.60), "مواد غذائية")
    _building(Vector3(-66,5.5,40), Vector3(16,11,12), Color(0.84,0.76,0.61), "محمصة")
    _building(Vector3(-49,6.0,52), Vector3(17,12,13), Color(0.78,0.71,0.59), "الحوش الليبي")

    # Palm trees and lamps.
    for p in [Vector3(-5,0,25),Vector3(14,0,25),Vector3(-24,0,25),Vector3(51,0,-2),Vector3(51,0,15),Vector3(-45,0,20)]:
        _palm(p)
    for p in [Vector3(-16,0,-1),Vector3(8,0,-1),Vector3(50,0,-12),Vector3(50,0,8),Vector3(18,0,24)]:
        _street_lamp(p)

    # A few parked / moving-look cars for street life.
    _simple_car(Vector3(15,0.55,3), Color(0.72,0.12,0.10), 0.0)
    _simple_car(Vector3(-22,0.55,13), Color(0.14,0.27,0.52), 180.0)
    _simple_car(Vector3(36,0.55,-29), Color(0.72,0.72,0.70), 90.0)

    target_marker = _marker(Vector3(-10,0.25,8), Color(1.0,0.72,0.0))

func _castle_block(pos: Vector3, size: Vector3, color: Color) -> void:
    _box(pos,size,color)
    for x in range(-24,25,8):
        _box(pos + Vector3(x,6.4,10.7), Vector3(3.5,1.2,1.0), color.lightened(0.06))

func _castle_tower(pos: Vector3) -> void:
    _box(pos,Vector3(12,18,12),Color(0.47,0.28,0.16))
    _box(pos + Vector3(0,9.4,0),Vector3(13,1.0,13),Color(0.52,0.32,0.18))

func _building(pos: Vector3, size: Vector3, color: Color, shop_name: String) -> void:
    _box(pos,size,color)
    for y in [2.7,5.8]:
        for x in [-4.2,0.0,4.2]:
            _box(pos + Vector3(x,y - size.y/2.0,-size.z/2.0 - 0.08),Vector3(1.7,1.8,0.13),Color(0.14,0.22,0.28))
    var sign := Label3D.new()
    sign.text = shop_name
    sign.position = pos + Vector3(0,-size.y/2.0 + 1.8,-size.z/2.0 - 0.16)
    sign.font_size = 42
    sign.modulate = Color(0.95,0.91,0.78)
    add_child(sign)

func _street_lamp(pos: Vector3) -> void:
    var pole := MeshInstance3D.new()
    var mesh := CylinderMesh.new()
    mesh.top_radius = 0.08
    mesh.bottom_radius = 0.11
    mesh.height = 5.5
    pole.mesh = mesh
    pole.position = pos + Vector3(0,2.75,0)
    var mat := StandardMaterial3D.new()
    mat.albedo_color = Color(0.10,0.11,0.12)
    pole.material_override = mat
    add_child(pole)
    _box(pos + Vector3(0.45,5.45,0),Vector3(0.95,0.12,0.18),Color(0.12,0.13,0.14))

func _simple_car(pos: Vector3, color: Color, yaw: float) -> void:
    var car := Node3D.new()
    car.position = pos
    car.rotation_degrees.y = yaw
    add_child(car)
    _child_box(car,Vector3.ZERO,Vector3(1.8,0.65,3.8),color)
    _child_box(car,Vector3(0,0.55,0.15),Vector3(1.45,0.55,1.7),Color(0.12,0.17,0.20))

func _build_taxi() -> void:
    taxi = Node3D.new()
    taxi.position = Vector3(2,0.72,14)
    add_child(taxi)

    _child_box(taxi,Vector3(0,0,0),Vector3(1.98,0.74,4.45),Color(0.96,0.69,0.025))
    _child_box(taxi,Vector3(0,0.62,0.22),Vector3(1.66,0.72,2.15),Color(0.115,0.16,0.19))
    _child_box(taxi,Vector3(0,0.96,0.02),Vector3(0.92,0.19,0.54),Color(0.97,0.91,0.67))
    _child_box(taxi,Vector3(0,0.37,-2.24),Vector3(1.45,0.24,0.08),Color(0.92,0.12,0.06))
    _child_box(taxi,Vector3(0,0.36,2.24),Vector3(1.45,0.24,0.08),Color(0.96,0.92,0.74))

    for x in [-1.02,1.02]:
        for z in [-1.45,1.45]:
            _wheel(Vector3(x,-0.30,z))

    camera = Camera3D.new()
    camera.position = Vector3(0,3.25,6.6)
    camera.rotation_degrees = Vector3(-14,0,0)
    camera.fov = 72.0
    camera.current = true
    taxi.add_child(camera)

func _wheel(pos: Vector3) -> void:
    var w := MeshInstance3D.new()
    var c := CylinderMesh.new()
    c.top_radius = 0.41
    c.bottom_radius = 0.41
    c.height = 0.27
    w.mesh = c
    w.position = pos
    w.rotation_degrees.z = 90
    var mat := StandardMaterial3D.new()
    mat.albedo_color = Color(0.018,0.018,0.020)
    mat.roughness = 0.9
    w.material_override = mat
    taxi.add_child(w)

func _palm(pos: Vector3) -> void:
    var trunk := MeshInstance3D.new()
    var cyl := CylinderMesh.new()
    cyl.top_radius = 0.16
    cyl.bottom_radius = 0.27
    cyl.height = 5.8
    trunk.mesh = cyl
    trunk.position = pos + Vector3(0,2.9,0)
    var tm := StandardMaterial3D.new()
    tm.albedo_color = Color(0.31,0.19,0.10)
    trunk.material_override = tm
    add_child(trunk)
    for a in range(0,360,40):
        var leaf := MeshInstance3D.new()
        var bm := BoxMesh.new()
        bm.size = Vector3(0.18,0.07,3.3)
        leaf.mesh = bm
        leaf.position = pos + Vector3(0,5.85,0)
        leaf.rotation_degrees = Vector3(-20,a,0)
        var lm := StandardMaterial3D.new()
        lm.albedo_color = Color(0.055,0.31,0.13)
        leaf.material_override = lm
        add_child(leaf)

func _box(pos: Vector3, size: Vector3, color: Color) -> MeshInstance3D:
    var m := MeshInstance3D.new()
    var b := BoxMesh.new()
    b.size = size
    m.mesh = b
    m.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = 0.82
    m.material_override = mat
    add_child(m)
    return m

func _child_box(parent: Node3D, pos: Vector3, size: Vector3, color: Color) -> MeshInstance3D:
    var m := MeshInstance3D.new()
    var b := BoxMesh.new()
    b.size = size
    m.mesh = b
    m.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = 0.62
    m.material_override = mat
    parent.add_child(m)
    return m

func _marker(pos: Vector3, color: Color) -> MeshInstance3D:
    var m := MeshInstance3D.new()
    var c := CylinderMesh.new()
    c.top_radius = 1.25
    c.bottom_radius = 1.25
    c.height = 0.15
    m.mesh = c
    m.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mat.emission_enabled = true
    mat.emission = color
    mat.emission_energy_multiplier = 2.2
    m.material_override = mat
    add_child(m)
    return m

func _update_target_marker() -> void:
    if target_marker == null or mission_manager == null:
        return
    target_marker.position = mission_manager.get_target_position() + Vector3(0,-0.5,0)
    var mat := target_marker.material_override as StandardMaterial3D
    var c: Color = mission_manager.get_target_color()
    mat.albedo_color = c
    mat.emission = c
    target_marker.rotation.y += 0.015

func _build_ui() -> void:
    var ui := CanvasLayer.new()
    add_child(ui)
    var root := Control.new()
    root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    root.mouse_filter = Control.MOUSE_FILTER_PASS
    ui.add_child(root)

    var top := PanelContainer.new()
    top.anchor_left = 0.018
    top.anchor_top = 0.022
    top.anchor_right = 0.982
    top.anchor_bottom = 0.155
    root.add_child(top)
    var row := HBoxContainer.new()
    row.add_theme_constant_override("separation",20)
    top.add_child(row)

    area_label = Label.new()
    area_label.text = "تاكسي طرابلس  •  ميدان الشهداء"
    area_label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    area_label.add_theme_font_size_override("font_size",22)
    row.add_child(area_label)

    info_label = Label.new()
    info_label.text = "امشِ للعلامة الصفرا، فيه زبون يستنى في الميدان"
    info_label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    info_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    info_label.add_theme_font_size_override("font_size",18)
    row.add_child(info_label)

    money_label = Label.new()
    money_label.text = "500 د.ل"
    money_label.custom_minimum_size = Vector2(115,0)
    money_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
    money_label.add_theme_font_size_override("font_size",21)
    row.add_child(money_label)

    var mini := PanelContainer.new()
    mini.anchor_left = 0.018
    mini.anchor_top = 0.19
    mini.anchor_right = 0.155
    mini.anchor_bottom = 0.38
    root.add_child(mini)
    var mini_text := Label.new()
    mini_text.text = "الميدان\n▲ إنت\n● المشوار"
    mini_text.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    mini_text.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    mini_text.add_theme_font_size_override("font_size",16)
    mini.add_child(mini_text)

    speed_label = Label.new()
    speed_label.text = "0 كم/س"
    speed_label.anchor_left = 0.44
    speed_label.anchor_top = 0.865
    speed_label.anchor_right = 0.56
    speed_label.anchor_bottom = 0.95
    speed_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    speed_label.add_theme_font_size_override("font_size",23)
    root.add_child(speed_label)

    _touch_button(root,"◀",Vector2(0.035,0.79),Vector2(0.095,0.94),func(v): steer_input = v,1.0)
    _touch_button(root,"▶",Vector2(0.108,0.79),Vector2(0.168,0.94),func(v): steer_input = v,-1.0)
    _touch_button(root,"فرامل",Vector2(0.785,0.79),Vector2(0.87,0.94),func(v): throttle_input = v,-1.0)
    _touch_button(root,"بنزين",Vector2(0.885,0.72),Vector2(0.975,0.94),func(v): throttle_input = v,1.0)

    var horn := Button.new()
    horn.text = "بوري"
    horn.anchor_left = 0.69
    horn.anchor_top = 0.82
    horn.anchor_right = 0.76
    horn.anchor_bottom = 0.94
    horn.add_theme_font_size_override("font_size",18)
    horn.pressed.connect(func(): audio_manager.horn(); audio_manager.ui_click())
    root.add_child(horn)

func _touch_button(root: Control, text_value: String, from: Vector2, to: Vector2, setter: Callable, value: float) -> void:
    var b := Button.new()
    b.text = text_value
    b.anchor_left = from.x
    b.anchor_top = from.y
    b.anchor_right = to.x
    b.anchor_bottom = to.y
    b.add_theme_font_size_override("font_size",21)
    b.button_down.connect(func(): setter.call(value); audio_manager.ui_click())
    b.button_up.connect(func(): setter.call(0.0))
    root.add_child(b)
