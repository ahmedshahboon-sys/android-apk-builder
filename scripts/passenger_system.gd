extends Node3D

var main: Node
var mission_manager: Node
var passenger: Node3D
var bubble: Label3D
var last_stage: int = -1
var idle_time: float = 0.0
var base_y: float = 0.0

func _ready() -> void:
    await get_tree().process_frame
    main = get_parent()
    if main == null:
        return
    mission_manager = main.get("mission_manager")
    _build_passenger()
    _sync_passenger()

func _process(delta: float) -> void:
    if mission_manager == null or passenger == null:
        return
    idle_time += delta
    if passenger.visible:
        passenger.rotation_degrees.y = sin(idle_time * 0.65) * 8.0
        passenger.position.y = base_y + sin(idle_time * 2.1) * 0.018
    var stage: int = int(mission_manager.get("stage"))
    if stage != last_stage:
        _sync_passenger()

func _build_passenger() -> void:
    passenger = Node3D.new()
    add_child(passenger)

    # Torso / jacket
    _part_box(Vector3(0,1.03,0), Vector3(0.58,0.78,0.30), Color(0.075,0.16,0.27), 0.82)
    _part_box(Vector3(0,0.76,0.155), Vector3(0.50,0.05,0.03), Color(0.80,0.68,0.45), 0.70)

    # Shirt collar
    _part_box(Vector3(0,1.37,0.155), Vector3(0.23,0.12,0.035), Color(0.92,0.91,0.87), 0.68)

    # Legs and shoes
    _part_box(Vector3(-0.17,0.39,0), Vector3(0.22,0.72,0.24), Color(0.105,0.115,0.13), 0.88)
    _part_box(Vector3(0.17,0.39,0), Vector3(0.22,0.72,0.24), Color(0.105,0.115,0.13), 0.88)
    _part_box(Vector3(-0.17,0.08,0.08), Vector3(0.25,0.13,0.43), Color(0.035,0.035,0.04), 0.74)
    _part_box(Vector3(0.17,0.08,0.08), Vector3(0.25,0.13,0.43), Color(0.035,0.035,0.04), 0.74)

    # Arms / hands
    _part_box(Vector3(-0.39,1.01,0), Vector3(0.18,0.70,0.20), Color(0.075,0.16,0.27), 0.82)
    _part_box(Vector3(0.39,1.01,0), Vector3(0.18,0.70,0.20), Color(0.075,0.16,0.27), 0.82)
    _part_sphere(Vector3(-0.39,0.63,0.03),0.105,Color(0.68,0.47,0.33))
    _part_sphere(Vector3(0.39,0.63,0.03),0.105,Color(0.68,0.47,0.33))

    # Neck and head
    _part_cylinder(Vector3(0,1.50,0),0.095,0.18,Color(0.68,0.47,0.33))
    _part_sphere(Vector3(0,1.72,0),0.245,Color(0.72,0.51,0.37))

    # Hair
    var hair: MeshInstance3D = _part_sphere(Vector3(0,1.88,-0.015),0.235,Color(0.055,0.045,0.04))
    hair.scale = Vector3(1.02,0.47,1.0)

    # Face details
    _part_sphere(Vector3(-0.082,1.75,0.218),0.025,Color(0.035,0.035,0.035))
    _part_sphere(Vector3(0.082,1.75,0.218),0.025,Color(0.035,0.035,0.035))
    _part_box(Vector3(0,1.64,0.235),Vector3(0.11,0.025,0.025),Color(0.30,0.12,0.09),0.9)

    bubble = Label3D.new()
    bubble.text = "تاكسي!"
    bubble.position = Vector3(0,2.28,0)
    bubble.font_size = 30
    bubble.modulate = Color(1.0,0.91,0.62)
    bubble.outline_size = 8
    bubble.outline_modulate = Color(0.035,0.04,0.05)
    bubble.billboard = BaseMaterial3D.BILLBOARD_ENABLED
    passenger.add_child(bubble)

func _part_box(pos: Vector3, size: Vector3, color: Color, roughness_value: float) -> MeshInstance3D:
    var mi: MeshInstance3D = MeshInstance3D.new()
    var mesh: BoxMesh = BoxMesh.new()
    mesh.size = size
    mi.mesh = mesh
    mi.position = pos
    var mat: StandardMaterial3D = StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = roughness_value
    mi.material_override = mat
    passenger.add_child(mi)
    return mi

func _part_sphere(pos: Vector3, radius_value: float, color: Color) -> MeshInstance3D:
    var mi: MeshInstance3D = MeshInstance3D.new()
    var mesh: SphereMesh = SphereMesh.new()
    mesh.radius = radius_value
    mesh.height = radius_value * 2.0
    mi.mesh = mesh
    mi.position = pos
    var mat: StandardMaterial3D = StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = 0.72
    mi.material_override = mat
    passenger.add_child(mi)
    return mi

func _part_cylinder(pos: Vector3, radius_value: float, height_value: float, color: Color) -> MeshInstance3D:
    var mi: MeshInstance3D = MeshInstance3D.new()
    var mesh: CylinderMesh = CylinderMesh.new()
    mesh.top_radius = radius_value
    mesh.bottom_radius = radius_value
    mesh.height = height_value
    mi.mesh = mesh
    mi.position = pos
    var mat: StandardMaterial3D = StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = 0.78
    mi.material_override = mat
    passenger.add_child(mi)
    return mi

func _sync_passenger() -> void:
    if mission_manager == null or passenger == null:
        return
    var stage: int = int(mission_manager.get("stage"))
    last_stage = stage
    if stage == 0:
        var pickup: Vector3 = mission_manager.get("active_pickup") as Vector3
        passenger.position = pickup
        base_y = passenger.position.y
        passenger.visible = true
        bubble.text = "تاكسي! بالله وقف"
    else:
        passenger.visible = false
