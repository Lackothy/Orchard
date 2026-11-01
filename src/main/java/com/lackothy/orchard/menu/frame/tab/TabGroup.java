// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Orchard.
package com.lackothy.orchard.menu.frame.tab;

import com.lackothy.orchard.menu.option.MenuGroup;


import java.util.List;

record TabGroup(String id, MenuGroup modOptions, List<Tab<?>> tabs) {
}
