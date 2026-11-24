"""Finder layout for the A Console distribution disk image."""

files = [defines["app"], defines["repository"]]
symlinks = {"Applications": "/Applications"}
icon_locations = {
    "A Console.app": (117, 151),
    "Applications": (289, 151),
    "GitHub Repository": (480, 151),
}
window_rect = ((160, 160), (578, 389))
grid_spacing = 64
icon_size = 50
text_size = 13
show_status_bar = False
show_toolbar = False
show_sidebar = False
format = "UDZO"
