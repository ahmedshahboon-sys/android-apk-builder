extends Node3D

var main: Node3D
var taxi: Node3D

func _ready() -> void:
    await get_tree().process_frame
    main = get_parent() as Node3D
    if main == null:
        return
    taxi = main.get("taxi") as Node3D
    _tune_environment()
    _add_square_details()
    _add_castle_details()
    _add_street_furniture()
    _add_facade_details()
    _add_taxi_details()

func _tune_environment() -> void:
    for child: Node in main.get_children():
        if child is WorldEnvironment:
            var we: WorldEnvironment = child as WorldEnvironment
            if we.environment != null:
                we.environment.tonemap_mode = Environment.TONE_MAPPER_FILMIC
                we.environment.ambient_light_energy = max(we.environment.ambient_light_energy, 0.62)
                we.environment.fog_enabled = true
                we.environment.fog_density = 0.0035
                we.environment.fog_light_color = Color(0.72,0.70,0.66)
                we.environment.glow_enabled = true
                we.environment.glow_intensity = 0.32
    for child: Node in main.get_children():
        if child is DirectionalLight3D:
            var sun: DirectionalLight3D = child as DirectionalLight3D
            sun.shadow_enabled = true
            sun.light_energy = max(sun.light_energy, 1.05)

func _add_square_details() -> void:
    # Stone grid and curb accents around Martyrs' Square.
    for x: int in range(-28,29,4):
        _box(Vector3(float(x),0.145,-7.0),Vector3(0.045,0.018,25.0),Color(0.62,0.57,0.48),0.95)
    for z: int in range(-18,7,4):
        _box(Vector3(-1.0,0.147,float(z)),Vector3(58.0,0.018,0.045),Color(0.62,0.57,0.48),0.95)

    # Crosswalks.
    for i: int in range(8):
        _box(Vector3(-19.0 + float(i)*1.05,0.095,20.2),Vector3(0.66,0.028,4.2),Color(0.90,0.88,0.80),0.72)
    for i: int in range(7):
        _box(Vector3(43.1,0.095,-18.0 + float(i)*1.05),Vector3(4.2,0.028,0.66),Color(0.90,0.88,0.80),0.72)

    # Reflective road studs.
    for x: int in range(-70,71,8):
        _emissive_box(Vector3(float(x),0.105,8.0),Vector3(0.16,0.035,0.16),Color(1.0,0.76,0.20),0.7)
    for z: int in range(-58,52,8):
        _emissive_box(Vector3(31.0,0.105,float(z)),Vector3(0.16,0.035,0.16),Color(1.0,0.76,0.20),0.7)

    # Bollards around the square.
    for p: Vector3 in [Vector3(-27,0,-19),Vector3(-20,0,-19),Vector3(12,0,-19),Vector3(19,0,-19),Vector3(-27,0,5),Vector3(19,0,5)]:
        _bollard(p)

func _add_castle_details() -> void:
    var stone: Color = Color(0.50,0.30,0.16)
    var trim: Color = Color(0.68,0.42,0.23)

    # Battlements and vertical masonry rhythm.
    for x: int in range(-61,-6,4):
        _box(Vector3(float(x),13.72,-20.58),Vector3(2.2,1.15,0.95),stone,0.90)
    for x: int in range(-57,-10,6):
        _box(Vector3(float(x),9.0,-20.52),Vector3(0.20,7.0,0.34),trim.darkened(0.15),0.92)

    # Windows with metallic frames and warm interior glow.
    for x: int in range(-54,-13,8):
        for y: float in [5.8,8.5]:
            _castle_window(Vector3(float(x),y,-20.64))

    # Gate arch approximation with layered trim.
    _box(Vector3(-34.0,3.0,-20.72),Vector3(6.6,5.8,0.52),Color(0.045,0.038,0.034),0.96)
    _box(Vector3(-34.0,6.10,-20.64),Vector3(7.8,0.46,0.72),trim,0.88)
    _box(Vector3(-37.75,3.6,-20.64),Vector3(0.46,6.8,0.72),trim,0.88)
    _box(Vector3(-30.25,3.6,-20.64),Vector3(0.46,6.8,0.72),trim,0.88)

    # Warm wall lights.
    for x: float in [-51.0,-42.0,-26.0,-17.0]:
        var light: OmniLight3D = OmniLight3D.new()
        light.position = Vector3(x,4.8,-20.0)
        light.light_color = Color(1.0,0.64,0.30)
        light.light_energy = 0.42
        light.omni_range = 5.0
        light.shadow_enabled = false
        add_child(light)
        _emissive_box(Vector3(x,4.8,-20.52),Vector3(0.18,0.35,0.16),Color(1.0,0.62,0.22),1.2)

func _castle_window(pos: Vector3) -> void:
    _box(pos,Vector3(1.7,2.2,0.18),Color(0.035,0.06,0.075),0.16)
    _box(pos + Vector3(0,0,0.11),Vector3(0.08,2.15,0.07),Color(0.56,0.39,0.24),0.65)
    _box(pos + Vector3(0,0,0.11),Vector3(1.65,0.08,0.07),Color(0.56,0.39,0.24),0.65)
    _emissive_box(pos + Vector3(0,0,-0.03),Vector3(1.35,1.8,0.05),Color(0.45,0.27,0.10),0.18)

func _add_street_furniture() -> void:
    for p: Vector3 in [Vector3(-16,0,-17),Vector3(-7,0,-17),Vector3(7,0,-17),Vector3(16,0,-17),Vector3(-18,0,3),Vector3(15,0,3)]:
        _bench(p)
    for p: Vector3 in [Vector3(-25,0,-2),Vector3(20,0,-2),Vector3(47,0,3),Vector3(47,0,20)]:
        _trash_bin(p)
    _kiosk(Vector3(25,0,-8))
    _kiosk(Vector3(-7,0,17))

func _bench(pos: Vector3) -> void:
    _box(pos + Vector3(0,0.48,0),Vector3(2.2,0.16,0.62),Color(0.33,0.20,0.11),0.88)
    _box(pos + Vector3(0,0.93,0.26),Vector3(2.2,0.70,0.12),Color(0.29,0.18,0.10),0.88)
    _box(pos + Vector3(-0.82,0.22,0),Vector3(0.12,0.45,0.55),Color(0.08,0.09,0.10),0.60)
    _box(pos + Vector3(0.82,0.22,0),Vector3(0.12,0.45,0.55),Color(0.08,0.09,0.10),0.60)

func _bollard(pos: Vector3) -> void:
    _cylinder(pos + Vector3(0,0.43,0),0.10,0.86,Color(0.12,0.13,0.14),0.55)
    _emissive_box(pos + Vector3(0,0.67,0.10),Vector3(0.12,0.12,0.03),Color(1.0,0.71,0.16),0.6)

func _trash_bin(pos: Vector3) -> void:
    _cylinder(pos + Vector3(0,0.52,0),0.30,1.0,Color(0.10,0.13,0.14),0.72)
    _cylinder(pos + Vector3(0,1.04,0),0.33,0.10,Color(0.18,0.19,0.20),0.54)

func _kiosk(pos: Vector3) -> void:
    _box(pos + Vector3(0,1.35,0),Vector3(3.2,2.7,2.0),Color(0.42,0.34,0.24),0.86)
    _box(pos + Vector3(0,1.45,-1.02),Vector3(2.3,1.25,0.08),Color(0.04,0.08,0.10),0.18)
    _box(pos + Vector3(0,2.83,0),Vector3(3.6,0.16,2.4),Color(0.16,0.18,0.19),0.54)

func _add_facade_details() -> void:
    var fronts: Array[Dictionary] = [
        {"origin":Vector3(52,5.5,-48.05),"w":12.0,"h":8.0},
        {"origin":Vector3(68,6.2,-31.05),"w":11.0,"h":9.5},
        {"origin":Vector3(69,5.5,11.95),"w":12.0,"h":8.0},
        {"origin":Vector3(55,6.2,34.95),"w":13.0,"h":9.5}
    ]
    for front: Dictionary in fronts:
        var origin: Vector3 = front["origin"] as Vector3
        var width: float = float(front["w"])
        var height: float = float(front["h"])
        _facade_grid(origin,width,height)

func _facade_grid(origin: Vector3, width: float, height: float) -> void:
    var cols: int = max(2,int(width / 2.4))
    var rows: int = max(2,int(height / 2.2))
    for cx: int in range(cols):
        for ry: int in range(rows):
            var px: float = origin.x - width*0.5 + 1.2 + float(cx) * (width/float(cols))
            var py: float = 2.6 + float(ry) * 2.15
            _box(Vector3(px,py,origin.z),Vector3(1.25,1.45,0.08),Color(0.035,0.075,0.10),0.13)
            _box(Vector3(px,py,origin.z-0.055),Vector3(0.06,1.38,0.035),Color(0.58,0.52,0.43),0.54)
            _box(Vector3(px,py,origin.z-0.055),Vector3(1.18,0.06,0.035),Color(0.58,0.52,0.43),0.54)

func _add_taxi_details() -> void:
    if taxi == null:
        return
    # Mirrors
    _taxi_box(Vector3(-1.08,0.69,0.72),Vector3(0.18,0.22,0.34),Color(0.055,0.06,0.065),0.32,0.42)
    _taxi_box(Vector3(1.08,0.69,0.72),Vector3(0.18,0.22,0.34),Color(0.055,0.06,0.065),0.32,0.42)
    # Door handles and belt line
    for x: float in [-0.87,0.87]:
        _taxi_box(Vector3(x,0.56,0.70),Vector3(0.055,0.07,0.40),Color(0.12,0.12,0.13),0.28,0.58)
        _taxi_box(Vector3(x,0.56,-0.78),Vector3(0.055,0.07,0.40),Color(0.12,0.12,0.13),0.28,0.58)
    _taxi_box(Vector3(0,0.49,0),Vector3(1.92,0.045,3.55),Color(0.12,0.12,0.13),0.30,0.48)
    # Hood / trunk creases
    _taxi_box(Vector3(0,0.46,1.72),Vector3(1.42,0.035,0.06),Color(0.72,0.42,0.01),0.34,0.22)
    _taxi_box(Vector3(0,0.46,-1.70),Vector3(1.42,0.035,0.06),Color(0.72,0.42,0.01),0.34,0.22)
    # Plate surrounds
    _taxi_box(Vector3(0,0.20,2.34),Vector3(0.92,0.30,0.055),Color(0.91,0.90,0.84),0.45,0.02)
    _taxi_box(Vector3(0,0.20,-2.34),Vector3(0.92,0.30,0.055),Color(0.91,0.90,0.84),0.45,0.02)

func _taxi_box(pos: Vector3,size: Vector3,color: Color,roughness_value: float,metallic_value: float) -> void:
    var mi: MeshInstance3D = MeshInstance3D.new()
    var mesh: BoxMesh = BoxMesh.new()
    mesh.size = size
    mi.mesh = mesh
    mi.position = pos
    var mat: StandardMaterial3D = StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = roughness_value
    mat.metallic = metallic_value
    mi.material_override = mat
    taxi.add_child(mi)

func _box(pos: Vector3,size: Vector3,color: Color,roughness_value: float) -> MeshInstance3D:
    var mi: MeshInstance3D = MeshInstance3D.new()
    var mesh: BoxMesh = BoxMesh.new()
    mesh.size = size
    mi.mesh = mesh
    mi.position = pos
    var mat: StandardMaterial3D = StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = roughness_value
    mi.material_override = mat
    add_child(mi)
    return mi

func _emissive_box(pos: Vector3,size: Vector3,color: Color,energy: float) -> void:
    var mi: MeshInstance3D = _box(pos,size,color,0.25)
    var mat: StandardMaterial3D = mi.material_override as StandardMaterial3D
    if mat != null:
        mat.emission_enabled = true
        mat.emission = color
        mat.emission_energy_multiplier = energy

func _cylinder(pos: Vector3,radius_value: float,height_value: float,color: Color,roughness_value: float) -> MeshInstance3D:
    var mi: MeshInstance3D = MeshInstance3D.new()
    var mesh: CylinderMesh = CylinderMesh.new()
    mesh.top_radius = radius_value
    mesh.bottom_radius = radius_value
    mesh.height = height_value
    mi.mesh = mesh
    mi.position = pos
    var mat: StandardMaterial3D = StandardMaterial3D.new()
    mat.albedo_color = color
    mat.roughness = roughness_value
    mi.material_override = mat
    add_child(mi)
    return mi
