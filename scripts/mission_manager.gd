extends Node

signal mission_text_changed(text: String)
signal money_changed(value: int)
signal pickup_sound
signal reward_sound

var money := 500
var job_index := 0
var stage := 0
var active_pickup := Vector3.ZERO
var active_dropoff := Vector3.ZERO
var reward := 25

var jobs := [
    {
        "pickup": Vector3(-10, 0.8, 8),
        "dropoff": Vector3(36, 0.8, -10),
        "reward": 25,
        "start": "امشِ للعلامة الصفرا، فيه زبون يستنى في الميدان",
        "onboard": "ركب الزبون.. توا خُش بيه لمدخل المدينة القديمة",
        "done": "يعطيك الصحة يا خوي، وصلنا.. المشوار تم +25 د.ل"
    },
    {
        "pickup": Vector3(30, 0.8, 26),
        "dropoff": Vector3(-42, 0.8, 31),
        "reward": 35,
        "start": "في زبون ثاني قريب من الساحة.. دور عليه عند العلامة الصفرا",
        "onboard": "قالك يبي يمشي للجهة الثانية من الميدان.. خليك هادي في السواقة",
        "done": "ممتاز.. الزبون نزل ودفع كاش +35 د.ل"
    },
    {
        "pickup": Vector3(-28, 0.8, 31),
        "dropoff": Vector3(48, 0.8, -2),
        "reward": 45,
        "start": "عندك مشوار ثالث.. واحد مستعجل شوية، امشيله",
        "onboard": "الزبون قالك: لو سمحت ما تكثرش لف، امشِ من الطريق القصير",
        "done": "وصلته في الوقت.. خدمة ميه ميه +45 د.ل"
    }
]

func _ready() -> void:
    _load_job(0)

func _load_job(index: int) -> void:
    job_index = index % jobs.size()
    var j: Dictionary = jobs[job_index]
    active_pickup = j["pickup"]
    active_dropoff = j["dropoff"]
    reward = int(j["reward"])
    stage = 0
    mission_text_changed.emit(String(j["start"]))

func update_position(pos: Vector3) -> void:
    var j: Dictionary = jobs[job_index]
    if stage == 0 and pos.distance_to(active_pickup) < 4.0:
        stage = 1
        mission_text_changed.emit(String(j["onboard"]))
        pickup_sound.emit()
    elif stage == 1 and pos.distance_to(active_dropoff) < 5.0:
        stage = 2
        money += reward
        money_changed.emit(money)
        mission_text_changed.emit(String(j["done"]))
        reward_sound.emit()
        await get_tree().create_timer(2.3).timeout
        _load_job(job_index + 1)

func get_target_position() -> Vector3:
    return active_pickup if stage == 0 else active_dropoff

func get_target_color() -> Color:
    return Color(1.0, 0.72, 0.0) if stage == 0 else Color(0.0, 0.85, 0.3)
