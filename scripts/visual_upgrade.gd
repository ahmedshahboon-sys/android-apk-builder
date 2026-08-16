extends Node3D

var main: Node3D
var taxi: Node3D
var traffic: Array[Node3D] = []
var traffic_axes: Array[int] = []
var traffic_speeds: Array[float] = []

func _ready() -> void:
    await get_tree().process_frame
    main = get_parent() as Node3D
    if main == null:
        return
    taxi = main.get("taxi") as Node3D
    _upgrade_square()
    _upgrade_castle()
    _upgrade_taxi()
    _add_city_life()

func _process(delta: float) -> void:
    _move_traffic(delta)

func _upgrade_square() -> void:
    _box(Vector3(-1.0,0.06,-7.0),Vector3(58.0,0.12,26.0),Color(0.73,0.66,0.52),0.95)
    for x: int in range(-26,27,4):
        _box(Vector3(float(x),0.13,-7.0),Vector3(0.07,0.025,25.0),Color(0.82,0.77,0.66),0.9)
    for z: int in range(-18,6,4):
        _box(Vector3(-1.0,0.132,float(z)),Vector3(57.0,0.025,0.07),Color(0.82,0.77,0.66),0.9)

    var fountain_base: MeshInstance3D = _cylinder(Vector3(-1.0,0.36,-7.0),4.2,0.45,Color(0.65,0.60,0.50))
    fountain_base.scale = Vector3(1.0,1.0,1.0)
    _cylinder(Vector3(-1.0,0.63,-7.0),3.45,0.16,Color(0.19,0.47,0.66))
    _cylinder(Vector3(-1.0,1.15,-7.0),0.38,1.2,Color(0.76,0.70,0.57))
    _cylinder(Vector3(-1.0,1.82,-7.0),1.15,0.14,Color(0.19,0.47,0.66))

    for p: Vector3 in [Vector3(-22,0,-18),Vector3(18,0,-18),Vector3(-22,0,4),Vector3(18,0,4)]:
        _decorative_lamp(p)

func _upgrade_castle() -> void:
    var wall_color: Color = Color(0.48,0.285,0.15)
    var trim_color: Color = Color(0.62,0.39,0.21)
    _box(Vector3(-34,12.85,-31),Vector3(60,0.65,22),trim_color,0.88)
    for x: int in range(-61,-6,5):
        _box(Vector3(float(x),13.65,-20.7),Vector3(2.4,1.2,1.15),wall_color,0.9)
    for x: int in range(-54,-13,8):
        _arched_window(Vector3(float(x),7.5,-20.62))
    _box(Vector3(-34,4.0,-20.48),Vector3(7.6,7.4,0.7),Color(0.055,0.045,0.038),0.95)
    _box(Vector3(-34,7.55,-20.15),Vector3(9.2,0.65,1.0),trim_color,0.9)

    var castle_name: Label3D = Label3D.new()
    castle_name.text = "السرايا الحمراء"
    castle_name.position = Vector3(-34,11.25,-19.8)
    castle_name.font_size = 54
    castle_name.modulate = Color(0.95,0.86,0.68)
    castle_name.outline_size = 10
    castle_name.outline_modulate = Color(0.12,0.08,0.05)
    add_child(castle_name)

func _upgrade_taxi() -> void:
    if taxi == null:
        return
    _child_box(taxi,Vector3(0,0.18,-2.29),Vector3(1.86,0.15,0.13),Color(0.12,0.12,0.12),0.6)
    _child_box(taxi,Vector3(0,0.18,2.29),Vector3(1.86,0.15,0.13),Color(0.14,0.14,0.14),0.6)
    _child_box(taxi,Vector3(-0.73,0.37,2.28),Vector3(0.48,0.27,0.08),Color(1.0,0.94,0.72),0.25)
    _child_box(taxi,Vector3(0.73,0.37,2.28),Vector3(0.48,0.27,0.08),Color(1.0,0.94,0.72),0.25)
    _child_box(taxi,Vector3(-0.73,0.38,-2.28),Vector3(0.48,0.28,0.08),Color(0.88,0.06,0.035),0.25)
    _child_box(taxi,Vector3(0.73,0.38,-2.28),Vector3(0.48,0.28,0.08),Color(0.88,0.06,0.035),0.25)

    _child_box(taxi,Vector3(0,0.82,0.96),Vector3(1.56,0.03,0.05),Color(0.78,0.84,0.87),0.2)
    _child_box(taxi,Vector3(0,0.82,-0.88),Vector3(1.56,0.03,0.05),Color(0.78,0.84,0.87),0.2)
    _child_box(taxi,Vector3(-0.86,0.34,0),Vector3(0.04,0.12,2.8),Color(0.12,0.12,0.12),0.4)
    _child_box(taxi,Vector3(0.86,0.34,0),Vector3(0.04,0.12,2.8),Color(0.12,0.12,0.12),0.4)

    var taxi_sign: Label3D = Label3D.new()
    taxi_sign.text = "تاكسي"
    taxi_sign.position = Vector3(0,1.12,0.02)
    taxi_sign.font_size = 30
    taxi_sign.modulate = Color(0.10,0.10,0.10)
    taxi_sign.billboard = BaseMaterial3D.BILLBOARD_DISABLED
    taxi.add_child(taxi_sign)

    for x: float in [-0.73,0.73]:
        var head: SpotLight3D = SpotLight3D.new()
        head.position = Vector3(x,0.42,2.18)
        head.rotation_degrees = Vector3(-4,180,0)
        head.light_color = Color(1.0,0.91,0.68)
        head.light_energy = 1.2
        head.spot_range = 18.0
        head.spot_angle = 32.0
        head.shadow_enabled = false
        taxi.add_child(head)

func _add_city_life() -> void:
    _traffic_car(Vector3(-36,0.53,13),Color(0.18,0.30,0.52),0,7.0)
    _traffic_car(Vector3(52,0.53,8),Color(0.75,0.74,0.70),0,-6.0)
    _traffic_car(Vector3(31,0.53,-52),Color(0.62,0.12,0.10),1,5.5)
    _traffic_car(Vector3(31,0.53,39),Color(0.12,0.18,0.20),1,-5.0)

    for p: Vector3 in [Vector3(-17,0.8,-14),Vector3(8,0.8,-15),Vector3(-25,0.8,2),Vector3(15,0.8,1),Vector3(-47,0.8,-18),Vector3(48,0.8,22)]:
        _pedestrian(p)

    _road_sign(Vector3(18,0,-2),"ميدان الشهداء")
    _road_sign(Vector3(46,0,-20),"وسط طرابلس")

func _traffic_car(pos: Vector3, color: Color, axis: int, speed_value: float) -> void:
    var car: Node3D = Node3D.new()
    car.position = pos
    if axis == 1:
        car.rotation_degrees.y = 90.0
    elif speed_value < 0.0:
        car.rotation_degrees.y = 180.0
    add_child(car)
    _child_box(car,Vector3.ZERO,Vector3(1.75,0.58,3.65),color,0.62)
    _child_box(car,Vector3(0,0.48,0.05),Vector3(1.38,0.48,1.58),Color(0.08,0.13,0.17),0.18)
    traffic.append(car)
    traffic_axes.append(axis)
    traffic_speeds.append(speed_value)

func _move_traffic(delta: float) -> void:
    for i: int in range(traffic.size()):
        var car: Node3D = traffic[i]
        var axis: int = traffic_axes[i]
        var speed_value: float = traffic_speeds[i]
        if axis == 0:
            car.position.x += speed_value * delta
            if car.position.x > 82.0:
                car.position.x = -82.0
            elif car.position.x < -82.0:
                car.position.x = 82.0
        else:
            car.position.z += speed_value * delta
            if car.position.z > 66.0:
                car.position.z = -66.0
            elif car.position.z < -66.0:
                car.position.z = 66.0

func _pedestrian(pos: Vector3) -> void:
    var body: MeshInstance3D = MeshInstance3D.new()
    var body_mesh: CapsuleMesh = CapsuleMesh.new()
    body_mesh.radius = 0.23
    body_mesh.height = 1.15
    body.mesh = body_mesh
    body.position = pos + Vector3(0,0.58,0)
    var body_mat: StandardMaterial3D = StandardMaterial3D.new()
    body_mat.albedo_color = Color(0.18,0.24,0.30)
    body.material_override = body_mat
    add_child(body)

    var head: MeshInstance3D = MeshInstance3D.new()
    var sphere: SphereMesh = SphereMesh.new()
    sphere.radius = 0.22
    sphere.height = 0.44
    head.mesh = sphere
    head.position = pos + Vector3(0,1.38,0)
    var head_mat: StandardMaterial3D = StandardMaterial3D.new()
    head_mat.albedo_color = Color(0.67,0.47,0.34)
    head.material_override = head_mat
    add_child(head)

func _road_sign(pos: Vector3, text_value: String) -> void:
    _cylinder(pos + Vector3(0,1.5,0),0.07,3.0,Color(0.15,0.16,0.17))
    var sign: Label3D = Label3D.new()
    sign.text = text_value
    sign.position = pos + Vector3(0,2.8,0)
    sign.font_size = 38
    sign.modulate = Color(0.95,0.95,0.90)
    sign.outline_size = 8
    sign.outline_modulate = Color(0.05,0.08,0.10)
    add_child(sign)

func _decorative_lamp(pos: Vector3) -> void:
    _cylinder(pos + Vector3(0,2.4,0),0.08,4.8,Color(0.10,0.11,0.12))
    var light: OmniLight3D = OmniLight3D.new()
    light.position = pos + Vector3(0,4.65,0)
    light.light_color = Color(1.0,0.78,0.48)
    light.light_energy = 0.7
    light.omni_range = 7.5
    light.shadow_enabled = false
    add_child(light)
    var bulb: MeshInstance3D = _sphere(pos + Vector3(0,4.65,0),0.16,Color(1.0,0.72,0.34))
    var bulb_mat: StandardMaterial3D = bulb.material_override as StandardMaterial3D
    if bulb_mat != null:
        bulb_mat.emission_enabled = true
        bulb_mat.emission = Color(1.0,0.52,0.18)
        bulb_mat.emission_energy_multiplier = 1.6

func _arched_window(pos: Vector3) -> void:
    _box(pos,Vector3(2.0,2.8,0.18),Color(0.09,0.12,0.13),0.3)
    _box(pos + Vector3(0,1.48,0),Vector3(2.3,0.28,0.24),Color(0.60,0.39,0.23),0.8)

func _box(pos: Vector3, size: Vector3, color: Color, roughness_value: float) -> MeshInstance3D:
    var mesh_instance: MeshInstance3D = MeshInstance3D.new()
    var mesh: BoxMesh = BoxMesh.new()
    mesh.size = size
    mesh_instance.mesh = mesh
    mesh_instance.position = pos
    var material: StandardMaterial3D = StandardMaterial3D.new()
    material.albedo_color = color
    material.roughness = roughness_value
    mesh_instance.material_override = material
    add_child(mesh_instance)
    return mesh_instance

func _child_box(parent: Node3D, pos: Vector3, size: Vector3, color: Color, roughness_value: float) -> MeshInstance3D:
    var mesh_instance: MeshInstance3D = MeshInstance3D.new()
    var mesh: BoxMesh = BoxMesh.new()
    mesh.size = size
    mesh_instance.mesh = mesh
    mesh_instance.position = pos
    var material: StandardMaterial3D = StandardMaterial3D.new()
    material.albedo_color = color
    material.roughness = roughness_value
    mesh_instance.material_override = material
    parent.add_child(mesh_instance)
    return mesh_instance

func _cylinder(pos: Vector3, radius_value: float, height_value: float, color: Color) -> MeshInstance3D:
    var mesh_instance: MeshInstance3D = MeshInstance3D.new()
    var mesh: CylinderMesh = CylinderMesh.new()
    mesh.top_radius = radius_value
    mesh.bottom_radius = radius_value
    mesh.height = height_value
    mesh_instance.mesh = mesh
    mesh_instance.position = pos
    var material: StandardMaterial3D = StandardMaterial3D.new()
    material.albedo_color = color
    material.roughness = 0.76
    mesh_instance.material_override = material
    add_child(mesh_instance)
    return mesh_instance

func _sphere(pos: Vector3, radius_value: float, color: Color) -> MeshInstance3D:
    var mesh_instance: MeshInstance3D = MeshInstance3D.new()
    var mesh: SphereMesh = SphereMesh.new()
    mesh.radius = radius_value
    mesh.height = radius_value * 2.0
    mesh_instance.mesh = mesh
    mesh_instance.position = pos
    var material: StandardMaterial3D = StandardMaterial3D.new()
    material.albedo_color = color
    material.roughness = 0.35
    mesh_instance.material_override = material
    add_child(mesh_instance)
    return mesh_instance
