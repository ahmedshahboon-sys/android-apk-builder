extends Node3D

var main: Node
var mission_manager: Node
var passenger: Node3D
var bubble: Label3D
var last_stage: int = -1

func _ready() -> void:
    await get_tree().process_frame
    main = get_parent()
    if main == null:
        return
    mission_manager = main.get("mission_manager")
    _build_passenger()
    _sync_passenger()

func _process(_delta: float) -> void:
    if mission_manager == null or passenger == null:
        return
    var stage: int = int(mission_manager.get("stage"))
    if stage != last_stage:
        _sync_passenger()

func _build_passenger() -> void:
    passenger = Node3D.new()
    add_child(passenger)

    var body: MeshInstance3D = MeshInstance3D.new()
    var capsule: CapsuleMesh = CapsuleMesh.new()
    capsule.radius = 0.28
    capsule.height = 1.25
    body.mesh = capsule
    body.position = Vector3(0,0.62,0)
    var body_mat: StandardMaterial3D = StandardMaterial3D.new()
    body_mat.albedo_color = Color(0.12,0.24,0.38)
    body.material_override = body_mat
    passenger.add_child(body)

    var head: MeshInstance3D = MeshInstance3D.new()
    var sphere: SphereMesh = SphereMesh.new()
    sphere.radius = 0.23
    sphere.height = 0.46
    head.mesh = sphere
    head.position = Vector3(0,1.47,0)
    var head_mat: StandardMaterial3D = StandardMaterial3D.new()
    head_mat.albedo_color = Color(0.70,0.50,0.36)
    head.material_override = head_mat
    passenger.add_child(head)

    bubble = Label3D.new()
    bubble.text = "تاكسي!"
    bubble.position = Vector3(0,2.05,0)
    bubble.font_size = 34
    bubble.modulate = Color(1.0,0.92,0.70)
    bubble.outline_size = 7
    bubble.outline_modulate = Color(0.08,0.08,0.08)
    passenger.add_child(bubble)

func _sync_passenger() -> void:
    if mission_manager == null or passenger == null:
        return
    var stage: int = int(mission_manager.get("stage"))
    last_stage = stage
    if stage == 0:
        var pickup: Vector3 = mission_manager.get("active_pickup") as Vector3
        passenger.position = pickup
        passenger.visible = true
        bubble.text = "تاكسي! بالله وقف"
    else:
        passenger.visible = false
