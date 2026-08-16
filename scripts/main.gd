extends Node3D

var taxi: Node3D
var camera: Camera3D
var speed := 0.0
var steer := 0.0
var throttle := 0.0
var money := 500
var mission_stage := 0
var info_label: Label
var money_label: Label
var speed_label: Label

func _ready() -> void:
    DisplayServer.screen_set_orientation(DisplayServer.SCREEN_LANDSCAPE)
    _build_world()
    _build_taxi()
    _build_ui()

func _process(delta: float) -> void:
    var keyboard_throttle := Input.get_axis("move_back", "move_forward")
    var keyboard_steer := Input.get_axis("move_right", "move_left")
    var final_throttle := throttle if abs(throttle) > 0.01 else keyboard_throttle
    var final_steer := steer if abs(steer) > 0.01 else keyboard_steer

    speed = move_toward(speed, final_throttle * 21.0, 15.0 * delta)
    if abs(speed) > 0.15:
        taxi.rotate_y(final_steer * 1.25 * delta * sign(speed))
    taxi.position += -taxi.transform.basis.z * speed * delta
    speed_label.text = "%d كم/س" % int(abs(speed) * 5.0)

    if mission_stage == 0 and taxi.position.distance_to(Vector3(-10, 0.8, 8)) < 4.0:
        mission_stage = 1
        info_label.text = "الزبون ركب — توجه إلى مدخل المدينة القديمة"
    elif mission_stage == 1 and taxi.position.distance_to(Vector3(36, 0.8, -10)) < 5.0:
        mission_stage = 2
        money += 25
        money_label.text = "%d د.ل" % money
        info_label.text = "تمت الرحلة بنجاح  +25 د.ل"

func _build_world() -> void:
    var env := WorldEnvironment.new()
    var e := Environment.new()
    e.background_mode = Environment.BG_COLOR
    e.background_color = Color(0.42, 0.69, 0.93)
    e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
    e.ambient_light_color = Color(1.0, 0.95, 0.84)
    e.ambient_light_energy = 0.9
    env.environment = e
    add_child(env)

    var sun := DirectionalLight3D.new()
    sun.rotation_degrees = Vector3(-52, -22, 0)
    sun.light_energy = 1.35
    sun.shadow_enabled = true
    add_child(sun)

    # Ground and main roads around the square.
    _box(Vector3(0, -0.18, 0), Vector3(180, 0.3, 145), Color(0.72, 0.68, 0.57))
    _box(Vector3(0, 0.00, 8), Vector3(155, 0.10, 20), Color(0.075, 0.08, 0.085))
    _box(Vector3(30, 0.01, -14), Vector3(20, 0.10, 112), Color(0.075, 0.08, 0.085))
    _box(Vector3(-28, 0.02, 31), Vector3(112, 0.10, 18), Color(0.075, 0.08, 0.085))

    # Pavements.
    _box(Vector3(0, 0.12, -3), Vector3(150, 0.22, 2.5), Color(0.86,0.84,0.76))
    _box(Vector3(0, 0.12, 19), Vector3(150, 0.22, 2.5), Color(0.86,0.84,0.76))

    # Stylised Red Castle blockout with towers and gate.
    _box(Vector3(-31, 6.0, -29), Vector3(52, 12, 20), Color(0.53, 0.34, 0.20))
    _box(Vector3(-51, 9.0, -23), Vector3(11, 18, 11), Color(0.43, 0.27, 0.16))
    _box(Vector3(-11, 9.0, -23), Vector3(11, 18, 11), Color(0.43, 0.27, 0.16))
    _box(Vector3(-31, 3.4, -18.8), Vector3(8, 6.8, 1.0), Color(0.12,0.10,0.08))

    # Surrounding city blocks.
    for p in [Vector3(48,5,-39), Vector3(63,6,-24), Vector3(62,5,16), Vector3(49,6,35), Vector3(-63,5,37), Vector3(-47,6,48)]:
        _building(p)

    # Palm-like street elements.
    for p in [Vector3(-2,0,25), Vector3(16,0,25), Vector3(-20,0,25), Vector3(48,0,-2)]:
        _palm(p)

    # Lane markings.
    for x in range(-65, 66, 12):
        _box(Vector3(x,0.08,8), Vector3(5.5,0.025,0.18), Color(0.92,0.88,0.72))

    _marker(Vector3(-10, 0.25, 8), Color(1.0,0.72,0.0))
    _marker(Vector3(36, 0.25, -10), Color(0.0,0.85,0.3))

func _building(pos: Vector3) -> void:
    _box(pos, Vector3(13,10,11), Color(0.80,0.75,0.66))
    for y in [3.0, 6.0]:
        for x in [-3.5, 0.0, 3.5]:
            _box(pos + Vector3(x, y - 5.0, -5.56), Vector3(1.5,1.8,0.12), Color(0.20,0.27,0.32))

func _palm(pos: Vector3) -> void:
    var trunk := MeshInstance3D.new()
    var cyl := CylinderMesh.new()
    cyl.top_radius = 0.18
    cyl.bottom_radius = 0.28
    cyl.height = 5.6
    trunk.mesh = cyl
    trunk.position = pos + Vector3(0,2.8,0)
    var tm := StandardMaterial3D.new()
    tm.albedo_color = Color(0.34,0.22,0.12)
    trunk.material_override = tm
    add_child(trunk)
    for a in range(0,360,45):
        var leaf := MeshInstance3D.new()
        var bm := BoxMesh.new()
        bm.size = Vector3(0.22,0.08,3.2)
        leaf.mesh = bm
        leaf.position = pos + Vector3(0,5.7,0)
        leaf.rotation_degrees = Vector3(-18,a,0)
        var lm := StandardMaterial3D.new()
        lm.albedo_color = Color(0.08,0.33,0.15)
        leaf.material_override = lm
        add_child(leaf)

func _build_taxi() -> void:
    taxi = Node3D.new()
    taxi.position = Vector3(2, 0.75, 14)
    add_child(taxi)

    _taxi_part(Vector3(0,0,0), Vector3(1.95,0.72,4.35), Color(0.94,0.68,0.035))
    _taxi_part(Vector3(0,0.63,0.25), Vector3(1.62,0.70,2.1), Color(0.12,0.17,0.21))
    _taxi_part(Vector3(0,0.92,0.05), Vector3(0.92,0.20,0.55), Color(0.96,0.90,0.72))

    for x in [-1.02, 1.02]:
        for z in [-1.42, 1.42]:
            _wheel(Vector3(x,-0.28,z))

    camera = Camera3D.new()
    camera.position = Vector3(0, 3.1, 6.3)
    camera.fov = 70.0
    camera.current = true
    taxi.add_child(camera)
    camera.look_at(taxi.global_position + Vector3(0,0.7,-3.0), Vector3.UP)

func _wheel(pos: Vector3) -> void:
    var w := MeshInstance3D.new()
    var c := CylinderMesh.new()
    c.top_radius = 0.40
    c.bottom_radius = 0.40
    c.height = 0.26
    w.mesh = c
    w.position = pos
    w.rotation_degrees.z = 90
    var mat := StandardMaterial3D.new()
    mat.albedo_color = Color(0.025,0.025,0.028)
    w.material_override = mat
    taxi.add_child(w)

func _taxi_part(pos: Vector3, size: Vector3, color: Color) -> void:
    var m := MeshInstance3D.new()
    var b := BoxMesh.new()
    b.size = size
    m.mesh = b
    m.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = 0.55
    m.material_override = mat
    taxi.add_child(m)

func _box(pos: Vector3, size: Vector3, color: Color) -> void:
    var m := MeshInstance3D.new()
    var b := BoxMesh.new()
    b.size = size
    m.mesh = b
    m.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = 0.9
    m.material_override = mat
    add_child(m)

func _marker(pos: Vector3, color: Color) -> void:
    var m := MeshInstance3D.new()
    var c := CylinderMesh.new()
    c.top_radius = 1.4
    c.bottom_radius = 1.4
    c.height = 0.18
    m.mesh = c
    m.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mat.emission_enabled = true
    mat.emission = color
    mat.emission_energy_multiplier = 2.0
    m.material_override = mat
    add_child(m)

func _build_ui() -> void:
    var ui := CanvasLayer.new()
    add_child(ui)

    var root := Control.new()
    root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    root.mouse_filter = Control.MOUSE_FILTER_PASS
    ui.add_child(root)

    var top_panel := PanelContainer.new()
    top_panel.anchor_left = 0.02
    top_panel.anchor_top = 0.025
    top_panel.anchor_right = 0.98
    top_panel.anchor_bottom = 0.16
    root.add_child(top_panel)

    var bar := HBoxContainer.new()
    bar.add_theme_constant_override("separation", 24)
    top_panel.add_child(bar)

    var title := Label.new()
    title.text = "تاكسي طرابلس  •  ميدان الشهداء"
    title.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    title.add_theme_font_size_override("font_size", 22)
    bar.add_child(title)

    info_label = Label.new()
    info_label.text = "توجه إلى العلامة الصفراء لاستلام أول زبون"
    info_label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    info_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    info_label.add_theme_font_size_override("font_size", 18)
    bar.add_child(info_label)

    money_label = Label.new()
    money_label.text = "500 د.ل"
    money_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
    money_label.custom_minimum_size = Vector2(120,0)
    money_label.add_theme_font_size_override("font_size", 21)
    bar.add_child(money_label)

    var mini := PanelContainer.new()
    mini.anchor_left = 0.02
    mini.anchor_top = 0.20
    mini.anchor_right = 0.18
    mini.anchor_bottom = 0.43
    root.add_child(mini)
    var mini_label := Label.new()
    mini_label.text = "الخريطة\n▲ أنت\n● الزبون"
    mini_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    mini_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    mini_label.add_theme_font_size_override("font_size", 17)
    mini.add_child(mini_label)

    speed_label = Label.new()
    speed_label.text = "0 كم/س"
    speed_label.anchor_left = 0.44
    speed_label.anchor_top = 0.87
    speed_label.anchor_right = 0.56
    speed_label.anchor_bottom = 0.96
    speed_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    speed_label.add_theme_font_size_override("font_size", 24)
    root.add_child(speed_label)

    _touch_button(root, "◀", Vector2(0.045,0.78), Vector2(0.105,0.93), func(v): steer = v, 1.0)
    _touch_button(root, "▶", Vector2(0.12,0.78), Vector2(0.18,0.93), func(v): steer = v, -1.0)
    _touch_button(root, "فرامل", Vector2(0.79,0.78), Vector2(0.875,0.94), func(v): throttle = v, -1.0)
    _touch_button(root, "بنزين", Vector2(0.89,0.72), Vector2(0.975,0.94), func(v): throttle = v, 1.0)

func _touch_button(root: Control, text_value: String, from: Vector2, to: Vector2, setter: Callable, value: float) -> void:
    var b := Button.new()
    b.text = text_value
    b.anchor_left = from.x
    b.anchor_top = from.y
    b.anchor_right = to.x
    b.anchor_bottom = to.y
    b.add_theme_font_size_override("font_size", 22)
    b.button_down.connect(func(): setter.call(value))
    b.button_up.connect(func(): setter.call(0.0))
    root.add_child(b)
