package ch.linkyard.mcp.example.demo.resources

private object AnimalBox:
  case class Animal(name: String, description: String):
    def uri: String = s"animal://$name"

  val animals: List[Animal] = List(
    Animal("Lion", "A powerful big cat known as the king of the jungle"),
    Animal("Elephant", "The largest land animal, famous for its memory and trunk"),
    Animal("Dolphin", "An intelligent aquatic mammal known for its playful nature"),
    Animal("Owl", "A nocturnal bird of prey with excellent night vision"),
    Animal("Penguin", "A flightless bird adapted to life in the water and cold climates"),
    Animal("Kangaroo", "A marsupial from Australia known for its jumping ability"),
    Animal("Giraffe", "The tallest land animal, recognized by its long neck"),
    Animal("Panda", "A bear native to China, famous for its love of bamboo"),
    Animal("Wolf", "A social canine known for living and hunting in packs"),
    Animal("Peacock", "A bird famous for its colorful and extravagant tail feathers"),
    Animal("Cheetah", "The fastest land animal, capable of incredible sprints"),
    Animal("Sloth", "A slow-moving mammal that spends most of its life hanging from trees"),
    Animal("Chameleon", "A lizard known for its ability to change color"),
    Animal("Octopus", "An intelligent sea creature with eight arms and problem-solving skills"),
    Animal("Hummingbird", "A tiny bird capable of hovering in place by rapidly flapping its wings"),
    Animal("Armadillo", "A small mammal with a protective armored shell"),
    Animal("Platypus", "A unique egg-laying mammal with a duck bill and beaver tail"),
    Animal("Rhinoceros", "A large, thick-skinned herbivore with one or two horns on its snout"),
    Animal("Red Fox", "A clever and adaptable mammal with a bushy tail"),
    Animal("Polar Bear", "A large bear native to the Arctic, excellent swimmer and hunter"),
  )
