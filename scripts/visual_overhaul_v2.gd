extends Node3D

var main: Node3D
var taxi: Node3D
var old_camera: Camera3D
var chase_camera: Camera3D

func _ready() -> void:
    await get_tree().process_frame
    main = get_parent() as Node3D
    if main == null:
        return
    taxi = main.get("taxi") as Node3D
    _fix_camera()
    _clean_existing_hud()
    _refine_taxi_shape()
    _refine_city_materials()

func _fix_camera() -> void:
    if taxi == null:
        return
    for child: Node in taxi.get_children():
        if child is Camera3D:
            old_camera = child as Camera3D
            old_camera.current = false
    chase_camera = Camera3D.new()
    chase_camera.position = Vector3(0.0, 4.15, 7.65)
    chase_camera.rotation_degrees = Vector3(-16.5, 0.0, 0.0)
    chase_camera.fov = 67.0
    chase_camera.near = 0.15
    chase_camera.current = true
    taxi.add_child(chase_camera)

func _clean_existing_hud() -> void:
    for child: Node in main.get_children():
        if child is CanvasLayer:
            _polish_tree(child)

func _polish_tree(node: Node) -> void:
    if node is Button:
        var button := node as Button
        _layout_drive_button(button)
    elif node is PanelContainer:
        var panel := node as PanelContainer
        var style := StyleBoxFlat.new()
        style.bg_color = Color(0.018, 0.024, 0.034, 0.76)
        style.border_color = Color(1.0, 0.72, 0.12, 0.26)
        style.set_border_width_all(1)
        style.corner_radius_top_left = 14
        style.corner_radius_top_right = 14
        style.corner_radius_bottom_left = 14
        style.corner_radius_bottom_right = 14
        panel.add_theme_stylebox_override("panel", style)
    elif node is Label:
        var label := node as Label
        if "كم/س" in label.text:
            label.visible = false
        label.add_theme_color_override("font_outline_color", Color(0.0,0.0,0.0,0.65))
        label.add_theme_constant_override("outline_size", 3)
    for child: Node in node.get_children():
        _polish_tree(child)

func _layout_drive_button(button: Button) -> void:
    var t := button.text.strip_edges()
    if t == "بنزين":
        button.anchor_left = 0.885
        button.anchor_top = 0.70
        button.anchor_right = 0.975
        button.anchor_bottom = 0.94
        _style_drive_button(button, Color(0.10,0.56,0.25,0.88))
    elif t == "فرامل":
        button.anchor_left = 0.785
        button.anchor_top = 0.76
        button.anchor_right = 0.865
        button.anchor_bottom = 0.94
        _style_drive_button(button, Color(0.72,0.16,0.12,0.88))
    elif t == "بوري":
        button.anchor_left = 0.705
        button.anchor_top = 0.82
        button.anchor_right = 0.765
        button.anchor_bottom = 0.94
        _style_drive_button(button, Color(0.11,0.15,0.20,0.86))
    elif t == "◀":
        button.anchor_left = 0.035
        button.anchor_top = 0.79
        button.anchor_right = 0.105
        button.anchor_bottom = 0.94
        _style_drive_button(button, Color(0.07,0.10,0.14,0.82))
    elif t == "▶":
        button.anchor_left = 0.115
        button.anchor_top = 0.79
        button.anchor_right = 0.185
        button.anchor_bottom = 0.94
        _style_drive_button(button, Color(0.07,0.10,0.14,0.82))

func _style_drive_button(button: Button, base_color: Color) -> void:
    button.add_theme_font_size_override("font_size", 19)
    var normal := StyleBoxFlat.new()
    normal.bg_color = base_color
    normal.border_color = Color(1.0,1.0,1.0,0.18)
    normal.set_border_width_all(1)
    normal.corner_radius_top_left = 22
    normal.corner_radius_top_right = 22
    normal.corner_radius_bottom_left = 22
    normal.corner_radius_bottom_right = 22
    normal.shadow_color = Color(0,0,0,0.30)
    normal.shadow_size = 5
    var pressed := normal.duplicate() as StyleBoxFlat
    pressed.bg_color = base_color.lightened(0.16)
    button.add_theme_stylebox_override("normal", normal)
    button.add_theme_stylebox_override("pressed", pressed)
    button.add_theme_stylebox_override("hover", normal)
    button.add_theme_stylebox_override("focus", normal)

func _refine_taxi_shape() -> void:
    if taxi == null:
        return
    _taxi_box(Vector3(0,0.26,1.55), Vector3(1.88,0.34,1.35), Color(0.95,0.62,0.02), 0.28, 0.20)
    _taxi_box(Vector3(0,0.30,-1.55), Vector3(1.90,0.38,1.30), Color(0.95,0.62,0.02), 0.28, 0.20)
    _taxi_box(Vector3(0,0.60,0.10), Vector3(1.72,0.18,2.05), Color(0.97,0.67,0.025), 0.30, 0.16)
    _taxi_box(Vector3(-0.965,0.28,0.0), Vector3(0.07,0.28,3.45), Color(0.05,0.055,0.06), 0.48, 0.10)
    _taxi_box(Vector3(0.965,0.28,0.0), Vector3(0.07,0.28,3.45), Color(0.05,0.055,0.06), 0.48, 0.10)
    _taxi_box(Vector3(0,0.13,2.26), Vector3(1.55,0.09,0.16), Color(0.12,0.12,0.13), 0.42, 0.35)
    _taxi_box(Vector3(0,0.13,-2.26), Vector3(1.55,0.09,0.16), Color(0.12,0.12,0.13), 0.42, 0.35)

func _taxi_box(pos: Vector3, size: Vector3, color: Color, roughness: float, metallic: float) -> void:
    var mi := MeshInstance3D.new()
    var mesh := BoxMesh.new()
    mesh.size = size
    mi.mesh = mesh
    mi.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = roughness
    mat.metallic = metallic
    mi.material_override = mat
    taxi.add_child(mi)

func _refine_city_materials() -> void:
    _stone_trim(Vector3(-34,12.95,-20.18), Vector3(58.0,0.44,0.28), Color(0.62,0.38,0.20))
    for x: int in range(-58,-9,7):
        _stone_trim(Vector3(float(x),8.8,-20.16), Vector3(3.8,0.22,0.24), Color(0.67,0.43,0.24))
        _stone_trim(Vector3(float(x),5.7,-20.15), Vector3(0.20,5.2,0.24), Color(0.43,0.27,0.16))
    for p: Vector3 in [Vector3(52,4.1,-48),Vector3(68,4.2,-31),Vector3(69,4.1,12),Vector3(55,4.2,35)]:
        _modern_window(p)

func _stone_trim(pos: Vector3, size: Vector3, color: Color) -> void:
    var mi := MeshInstance3D.new()
    var mesh := BoxMesh.new()
    mesh.size = size
    mi.mesh = mesh
    mi.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = 0.88
    mi.material_override = mat
    add_child(mi)

func _modern_window(pos: Vector3) -> void:
    var mi := MeshInstance3D.new()
    var mesh := BoxMesh.new()
    mesh.size = Vector3(7.4,3.5,0.12)
    mi.mesh = mesh
    mi.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = Color(0.035,0.075,0.105,0.92)
    mat.metallic = 0.35
    mat.roughness = 0.12
    mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
    mi.material_override = mat
    add_child(mi)
