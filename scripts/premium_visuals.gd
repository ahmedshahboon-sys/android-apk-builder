extends Node3D

var main: Node3D
var taxi: Node3D
var hud_layer: CanvasLayer
var speed_ring: ProgressBar
var speed_value: Label
var gear_label: Label
var district_label: Label
var vignette: ColorRect

func _ready() -> void:
    await get_tree().process_frame
    main = get_parent() as Node3D
    if main == null:
        return
    taxi = main.get("taxi") as Node3D
    _polish_existing_ui()
    _build_premium_hud()
    _refine_taxi()
    _refine_square_facades()
    _add_atmosphere()

func _process(_delta: float) -> void:
    if main == null:
        return
    var raw_speed: float = abs(float(main.get("speed"))) * 5.2
    if speed_ring != null:
        speed_ring.value = clamp(raw_speed, 0.0, 180.0)
    if speed_value != null:
        speed_value.text = "%03d" % int(raw_speed)
    if gear_label != null:
        var s: float = float(main.get("speed"))
        gear_label.text = "R" if s < -0.4 else ("D" if s > 0.4 else "N")

func _polish_existing_ui() -> void:
    for child: Node in main.get_children():
        if child is CanvasLayer:
            _style_control_tree(child)

func _style_control_tree(node: Node) -> void:
    if node is PanelContainer:
        var panel := node as PanelContainer
        var style := StyleBoxFlat.new()
        style.bg_color = Color(0.025,0.035,0.05,0.82)
        style.border_color = Color(0.95,0.68,0.08,0.32)
        style.set_border_width_all(1)
        style.corner_radius_top_left = 18
        style.corner_radius_top_right = 18
        style.corner_radius_bottom_left = 18
        style.corner_radius_bottom_right = 18
        style.content_margin_left = 18.0
        style.content_margin_right = 18.0
        style.content_margin_top = 12.0
        style.content_margin_bottom = 12.0
        panel.add_theme_stylebox_override("panel",style)
    elif node is Button:
        _style_button(node as Button)
    elif node is Label:
        var label := node as Label
        label.add_theme_color_override("font_color",Color(0.96,0.97,1.0))
        label.add_theme_color_override("font_shadow_color",Color(0,0,0,0.75))
        label.add_theme_constant_override("shadow_offset_x",1)
        label.add_theme_constant_override("shadow_offset_y",2)
    for child: Node in node.get_children():
        _style_control_tree(child)

func _style_button(button: Button) -> void:
    var normal := StyleBoxFlat.new()
    normal.bg_color = Color(0.055,0.075,0.105,0.90)
    normal.border_color = Color(0.95,0.68,0.08,0.55)
    normal.set_border_width_all(2)
    normal.corner_radius_top_left = 20
    normal.corner_radius_top_right = 20
    normal.corner_radius_bottom_left = 20
    normal.corner_radius_bottom_right = 20
    var pressed := normal.duplicate() as StyleBoxFlat
    pressed.bg_color = Color(0.92,0.60,0.06,0.95)
    pressed.border_color = Color(1.0,0.86,0.42,0.9)
    var hover := normal.duplicate() as StyleBoxFlat
    hover.bg_color = Color(0.09,0.12,0.17,0.96)
    button.add_theme_stylebox_override("normal",normal)
    button.add_theme_stylebox_override("pressed",pressed)
    button.add_theme_stylebox_override("hover",hover)
    button.add_theme_stylebox_override("focus",hover)
    button.add_theme_color_override("font_color",Color(0.98,0.98,1.0))
    button.add_theme_color_override("font_pressed_color",Color(0.06,0.06,0.07))

func _build_premium_hud() -> void:
    hud_layer = CanvasLayer.new()
    hud_layer.layer = 20
    add_child(hud_layer)
    var root := Control.new()
    root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    root.mouse_filter = Control.MOUSE_FILTER_IGNORE
    hud_layer.add_child(root)

    vignette = ColorRect.new()
    vignette.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    vignette.color = Color(0.01,0.015,0.025,0.08)
    vignette.mouse_filter = Control.MOUSE_FILTER_IGNORE
    root.add_child(vignette)

    var badge := PanelContainer.new()
    badge.anchor_left = 0.018
    badge.anchor_top = 0.17
    badge.anchor_right = 0.185
    badge.anchor_bottom = 0.245
    var badge_style := StyleBoxFlat.new()
    badge_style.bg_color = Color(0.025,0.03,0.045,0.84)
    badge_style.border_color = Color(0.96,0.68,0.08,0.55)
    badge_style.set_border_width_all(1)
    badge_style.corner_radius_top_left = 16
    badge_style.corner_radius_top_right = 16
    badge_style.corner_radius_bottom_left = 16
    badge_style.corner_radius_bottom_right = 16
    badge.add_theme_stylebox_override("panel",badge_style)
    root.add_child(badge)
    district_label = Label.new()
    district_label.text = "TRIPOLI  •  ميدان الشهداء"
    district_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    district_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    district_label.add_theme_font_size_override("font_size",15)
    district_label.add_theme_color_override("font_color",Color(1.0,0.82,0.35))
    badge.add_child(district_label)

    var cluster := Control.new()
    cluster.anchor_left = 0.425
    cluster.anchor_top = 0.805
    cluster.anchor_right = 0.575
    cluster.anchor_bottom = 0.985
    root.add_child(cluster)

    var cluster_bg := PanelContainer.new()
    cluster_bg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    var cstyle := StyleBoxFlat.new()
    cstyle.bg_color = Color(0.015,0.02,0.03,0.90)
    cstyle.border_color = Color(0.95,0.67,0.08,0.65)
    cstyle.set_border_width_all(2)
    cstyle.corner_radius_top_left = 34
    cstyle.corner_radius_top_right = 34
    cstyle.corner_radius_bottom_left = 34
    cstyle.corner_radius_bottom_right = 34
    cluster_bg.add_theme_stylebox_override("panel",cstyle)
    cluster.add_child(cluster_bg)

    speed_ring = ProgressBar.new()
    speed_ring.min_value = 0
    speed_ring.max_value = 180
    speed_ring.show_percentage = false
    speed_ring.anchor_left = 0.10
    speed_ring.anchor_top = 0.66
    speed_ring.anchor_right = 0.90
    speed_ring.anchor_bottom = 0.82
    var bg := StyleBoxFlat.new()
    bg.bg_color = Color(0.13,0.15,0.18,0.9)
    bg.corner_radius_top_left = 8
    bg.corner_radius_top_right = 8
    bg.corner_radius_bottom_left = 8
    bg.corner_radius_bottom_right = 8
    var fill := StyleBoxFlat.new()
    fill.bg_color = Color(0.98,0.65,0.06,1.0)
    fill.corner_radius_top_left = 8
    fill.corner_radius_top_right = 8
    fill.corner_radius_bottom_left = 8
    fill.corner_radius_bottom_right = 8
    speed_ring.add_theme_stylebox_override("background",bg)
    speed_ring.add_theme_stylebox_override("fill",fill)
    cluster.add_child(speed_ring)

    speed_value = Label.new()
    speed_value.text = "000"
    speed_value.anchor_left = 0.08
    speed_value.anchor_top = 0.08
    speed_value.anchor_right = 0.72
    speed_value.anchor_bottom = 0.62
    speed_value.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    speed_value.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    speed_value.add_theme_font_size_override("font_size",38)
    speed_value.add_theme_color_override("font_color",Color(0.98,0.98,1.0))
    cluster.add_child(speed_value)

    var kmh := Label.new()
    kmh.text = "KM/H"
    kmh.anchor_left = 0.15
    kmh.anchor_top = 0.47
    kmh.anchor_right = 0.65
    kmh.anchor_bottom = 0.66
    kmh.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    kmh.add_theme_font_size_override("font_size",11)
    kmh.add_theme_color_override("font_color",Color(0.58,0.64,0.72))
    cluster.add_child(kmh)

    gear_label = Label.new()
    gear_label.text = "N"
    gear_label.anchor_left = 0.72
    gear_label.anchor_top = 0.16
    gear_label.anchor_right = 0.95
    gear_label.anchor_bottom = 0.58
    gear_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    gear_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    gear_label.add_theme_font_size_override("font_size",28)
    gear_label.add_theme_color_override("font_color",Color(1.0,0.72,0.12))
    cluster.add_child(gear_label)

func _refine_taxi() -> void:
    if taxi == null:
        return
    _car_part(Vector3(0,0.12,0.10),Vector3(1.93,0.16,4.20),Color(0.94,0.60,0.02),0.35,0.22)
    _car_part(Vector3(0,0.56,0.18),Vector3(1.72,0.12,2.42),Color(0.98,0.70,0.035),0.32,0.18)
    _glass(Vector3(0,0.78,0.92),Vector3(1.48,0.66,0.08),-18.0)
    _glass(Vector3(0,0.78,-0.90),Vector3(1.48,0.64,0.08),18.0)
    _glass(Vector3(-0.84,0.66,0.02),Vector3(0.06,0.54,1.66),0.0)
    _glass(Vector3(0.84,0.66,0.02),Vector3(0.06,0.54,1.66),0.0)
    _car_part(Vector3(0,0.18,2.26),Vector3(1.78,0.22,0.16),Color(0.055,0.06,0.07),0.45,0.05)
    _car_part(Vector3(0,0.16,-2.26),Vector3(1.82,0.18,0.16),Color(0.055,0.06,0.07),0.45,0.05)
    for x: float in [-0.69,0.69]:
        _emissive_part(Vector3(x,0.38,2.30),Vector3(0.44,0.22,0.09),Color(1.0,0.91,0.64))
        _emissive_part(Vector3(x,0.38,-2.30),Vector3(0.42,0.22,0.09),Color(0.95,0.05,0.02))
    _car_part(Vector3(0,1.08,0),Vector3(0.92,0.18,0.48),Color(0.98,0.81,0.23),0.4,0.1)
    var plate := Label3D.new()
    plate.text = "طرابلس  218"
    plate.position = Vector3(0,0.18,-2.36)
    plate.rotation_degrees.y = 180
    plate.font_size = 24
    plate.modulate = Color(0.06,0.06,0.06)
    taxi.add_child(plate)

func _car_part(pos: Vector3,size: Vector3,color: Color,roughness: float,metallic: float) -> void:
    var mesh_instance := MeshInstance3D.new()
    var mesh := BoxMesh.new()
    mesh.size = size
    mesh_instance.mesh = mesh
    mesh_instance.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = roughness
    mat.metallic = metallic
    mesh_instance.material_override = mat
    taxi.add_child(mesh_instance)

func _glass(pos: Vector3,size: Vector3,pitch: float) -> void:
    var mesh_instance := MeshInstance3D.new()
    var mesh := BoxMesh.new()
    mesh.size = size
    mesh_instance.mesh = mesh
    mesh_instance.position = pos
    mesh_instance.rotation_degrees.x = pitch
    var mat := StandardMaterial3D.new()
    mat.albedo_color = Color(0.045,0.095,0.13,0.72)
    mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
    mat.metallic = 0.25
    mat.roughness = 0.12
    mesh_instance.material_override = mat
    taxi.add_child(mesh_instance)

func _emissive_part(pos: Vector3,size: Vector3,color: Color) -> void:
    var mesh_instance := MeshInstance3D.new()
    var mesh := BoxMesh.new()
    mesh.size = size
    mesh_instance.mesh = mesh
    mesh_instance.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mat.emission_enabled = true
    mat.emission = color
    mat.emission_energy_multiplier = 1.8
    mat.roughness = 0.18
    mesh_instance.material_override = mat
    taxi.add_child(mesh_instance)

func _refine_square_facades() -> void:
    var stone := Color(0.68,0.52,0.34)
    for x: int in range(-59,-8,6):
        _world_box(Vector3(float(x),10.8,-20.30),Vector3(3.7,0.28,0.22),stone)
        _world_box(Vector3(float(x),6.2,-20.26),Vector3(0.26,6.4,0.22),stone.darkened(0.12))
    for p: Vector3 in [Vector3(58,3.3,-48),Vector3(68,3.3,-31),Vector3(69,3.3,12),Vector3(55,3.3,35)]:
        _shopfront(p)

func _shopfront(pos: Vector3) -> void:
    _world_box(pos,Vector3(8.6,4.6,0.22),Color(0.08,0.12,0.15))
    for x: float in [-2.8,0.0,2.8]:
        _world_box(pos + Vector3(x,0,0.13),Vector3(0.12,4.4,0.12),Color(0.72,0.63,0.46))

func _world_box(pos: Vector3,size: Vector3,color: Color) -> void:
    var mi := MeshInstance3D.new()
    var mesh := BoxMesh.new()
    mesh.size = size
    mi.mesh = mesh
    mi.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = 0.68
    mi.material_override = mat
    add_child(mi)

func _add_atmosphere() -> void:
    var fog := WorldEnvironment.new()
    var env := Environment.new()
    env.background_mode = Environment.BG_COLOR
    env.background_color = Color(0.39,0.62,0.83)
    env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
    env.ambient_light_color = Color(0.95,0.87,0.72)
    env.ambient_light_energy = 0.55
    env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
    env.glow_enabled = true
    env.glow_intensity = 0.55
    env.fog_enabled = true
    env.fog_light_color = Color(0.74,0.70,0.62)
    env.fog_light_energy = 0.25
    env.fog_density = 0.0016
    fog.environment = env
    add_child(fog)
