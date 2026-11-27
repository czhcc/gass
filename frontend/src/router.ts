import { createRouter, createWebHashHistory } from "vue-router";
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: "/:tab(worlds|actions|rules|models|scenarios|studio|demos)?",
      component: { render: () => null },
    },
  ],
});
