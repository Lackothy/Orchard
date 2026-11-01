#ifndef LODMESH_H
#define LODMESH_H

#include <stdint.h>

#define LM_TILE 64
#define LM_GROUPS 16
#define LM_HDR 32
#define LM_DEEP (-512)

enum {
	LM_G_TOP = 0, LM_G_BOTTOM = 1, LM_G_XP = 2, LM_G_XN = 3, LM_G_ZP = 4, LM_G_ZN = 5,
	LM_G_BXP = 6, LM_G_BXN = 7, LM_G_BZP = 8, LM_G_BZN = 9, LM_G_SXP = 10, LM_G_SXN = 11, LM_G_SZP = 12, LM_G_SZN = 13,
	LM_G_PLANT = 14, LM_G_INFO = 15
};

enum { LM_F_TOP = 0, LM_F_XP = 1, LM_F_XN = 2, LM_F_ZP = 3, LM_F_ZN = 4, LM_F_BOTTOM = 5, LM_F_PLANT = 6 };
enum { LM_K_GROUND = 0, LM_K_CROWN = 1, LM_K_UNDER = 2 };

typedef struct {
	const uint32_t *geom;
	const uint32_t *crown;
	const uint32_t *runs;
	const uint32_t *plantA;
	const uint32_t *plantB;
	int logN;
	int level;
	int tx, tz;
	int neighbors;
	int block;
} LmIn;

int lm_mesh(const LmIn *in, uint32_t *header, uint32_t *quads, int cap);

#endif
