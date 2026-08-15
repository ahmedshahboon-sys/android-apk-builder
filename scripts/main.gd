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

func _ready() -> void:
    _build_world()
    _build_taxi()
    _build_ui()

func _process(delta: float) -> void:
    speed = move_toward(speed, throttle * 18.0, 12.0 * delta)
    if abs(speed) > 0.1:
        taxi.rotate_y(steer * 1.4 * delta * sign(speed))
    taxi.position += -taxi.transform.basis.z * speed * delta
    if mission_stage == 0 and taxi.position.distance_to(Vector3(-10, 0.8, 8)) < 4.0:
        mission_stage = 1
        info_label.text = "الزبون ركب — توجه إلى المدينة القديمة"
    elif mission_stage == 1 and taxi.position.distance_to(Vector3(36, 0.8, -10)) < 5.0:
        mission_stage = 2
        money += 25
        money_label.text = "الرصيد: %d د.ل" % money
        info_label.text = "تمت الرحلة +25 د.ل"

func _build_world() -> void:
    var env := WorldEnvironment.new()
    var e := Environment.new()
    e.background_mode = Environment.BG_COLOR
    e.background_color = Color(0.55, 0.78, 0.97)
    e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
    e.ambient_light_color = Color(1.0, 0.95, 0.85)
    e.ambient_light_energy = 0.7
    env.environment = e
    add_child(env)

    var sun := DirectionalLight3D.new()
    sun.rotation_degrees = Vector3(-55, -25, 0)
    sun.light_energy = 1.2
    sun.shadow_enabled = true
    add_child(sun)

    _box(Vector3(0, -0.15, 0), Vector3(160, 0.3, 130), Color(0.64, 0.60, 0.52))
    _box(Vector3(0, 0.02, 8), Vector3(145, 0.12, 18), Color(0.08, 0.085, 0.09))
    _box(Vector3(28, 0.03, -8), Vector3(18, 0.12, 105), Color(0.08, 0.085, 0.09))

    _box(Vector3(-28, 6, -27), Vector3(48, 12, 20), Color(0.52, 0.34, 0.20))
    _box(Vector3(-47, 8.5, -22), Vector3(10, 17, 10), Color(0.43, 0.28, 0.16))
    _box(Vector3(-9, 8.5, -22), Vector3(10, 17, 10), Color(0.43, 0.28, 0.16))

    for p in [Vector3(48,5,-35), Vector3(61,5,-22), Vector3(60,5,18), Vector3(-58,5,35)]:
        _box(p, Vector3(13,10,11), Color(0.78,0.73,0.64))

    _marker(Vector3(-10, 0.25, 8), Color(1.0,0.75,0.0))
    _marker(Vector3(36, 0.25, -10), Color(0.0,0.85,0.3))

func _build_taxi() -> void:
    taxi = Node3D.new()
    taxi.position = Vector3(2, 0.8, 14)
    add_child(taxi)
    var body := MeshInstance3D.new()
    var mesh := BoxMesh.new()
    mesh.size = Vector3(1.9, 1.2, 4.2)
    body.mesh = mesh
    var mat := StandardMaterial3D.new()
    mat.albedo_color = Color(0.93, 0.71, 0.05)
    body.material_override = mat
    taxi.add_child(body)
    _taxi_part(Vector3(0,0.85,0.35), Vector3(1.45,0.8,2.0), Color(0.12,0.15,0.18))
    camera = Camera3D.new()
    camera.position = Vector3(0, 3.5, 7.5)
    camera.rotation_degrees = Vector3(-16, 180, 0)
    camera.current = true
    taxi.add_child(camera)

func _taxi_part(pos: Vector3, size: Vector3, color: Color) -> void:
    var m := MeshInstance3D.new()
    var b := BoxMesh.new()
    b.size = size
    m.mesh = b
    m.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
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
    var title := Label.new()
    title.text = "تاكسي طرابلس — ميدان الشهداء"
    title.position = Vector2(24, 18)
    title.add_theme_font_size_override("font_size", 24)
    ui.add_child(title)
    info_label = Label.new()
    info_label.text = "توجه إلى العلامة الصفراء لاستلام أول زبون"
    info_label.position = Vector2(24, 58)
    info_label.add_theme_font_size_override("font_size", 19)
    ui.add_child(info_label)
    money_label = Label.new()
    money_label.text = "الرصيد: 500 د.ل"
    money_label.position = Vector2(1030, 22)
    money_label.add_theme_font_size_override("font_size", 22)
    ui.add_child(money_label)
    _button(ui, "▲", Vector2(135, 535), func(v): throttle = v, 1.0)
    _button(ui, "▼", Vector2(135, 625), func(v): throttle = v, -1.0)
    _button(ui, "◀", Vector2(45, 625), func(v): steer = v, 1.0)
    _button(ui, "▶", Vector2(225, 625), func(v): steer = v, -1.0)

func _button(ui: CanvasLayer, text_value: String, pos: Vector2, setter: Callable, value: float) -> void:
    var b := Button.new()
    b.text = text_value
    b.position = pos
    b.size = Vector2(80, 70)
    b.add_theme_font_size_override("font_size", 28)
    b.button_down.connect(func(): setter.call(value))
    b.button_up.connect(func(): setter.call(0.0))
    ui.add_child(b)
