import { createRouter, createWebHashHistory } from "vue-router";
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: "/:tab(worlds|actions|models|scenarios|studio|demos)?",
      component: { render: () => null },
    },
  ],
});
