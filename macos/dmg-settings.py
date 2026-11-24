"""Finder layout for the A Console distribution disk image."""

files = [defines["app"], defines["repository"]]
symlinks = {"Applications": "/Applications"}
hide_extensions = ["GitHub Repository.webloc"]
icon_locations = {
    "A Console.app": (117, 170),
    "Applications": (289, 170),
    "GitHub Repository.webloc": (480, 170),
}
window_rect = ((160, 160), (578, 389))
grid_spacing = 64
icon_size = 40
text_size = 13
show_status_bar = False
show_toolbar = False
show_sidebar = False
format = "UDZO"
